package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.shared.TextSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object VoiceCorrection {

    private const val TAG = "VoiceCorrection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private var db: VoiceCorrectionDb? = null
    private var recorder: VoiceCorrectionRecorder? = null
    private var engine: VoiceCorrectionEngine? = null
    private var vocabularyBuilder: TypedVocabularyBuilder? = null

    private var segment: ((String) -> List<String>)? = null
    private var rank: ((String) -> Int?)? = null

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        initialized = true

        db = VoiceCorrectionDb.getInstance(app)
        val database = db
        if (database == null) {
            Log.w(TAG, "database unavailable — correction disabled for this process")
            return
        }

        val segmenter = TextSegmenter.getInstance(app)
        val segment: (String) -> List<String> = { segmenter.segment(it) }
        val posTag: (String) -> List<Pair<String, String>> = { segmenter.posTag(it) }
        val background = BackgroundWordFrequency.getInstance(app)
        val rank: (String) -> Int? = { background.rank(it) }
        this.segment = segment
        this.rank = rank

        recorder = VoiceCorrectionRecorder(app, database, segment)

        val repository = (app as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
        if (repository != null) {
            engine = VoiceCorrectionEngine(
                sources = listOf(
                    ConfusionDictionarySource(database),
                    TypedVocabularySource(database),
                ),
                strategy = LlmCorrectionStrategy(app, repository),
                db = database,
                segment = segment,
                consent = { VoiceCorrectionConsent.isEnabled(app) },
            )
        }

        val dao = runCatching {
            com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao()
        }.getOrNull()
        if (dao != null) {
            vocabularyBuilder = TypedVocabularyBuilder(app, database, dao, posTag, segment, rank)
        }
    }

    fun warmUp(context: Context) {
        ensureInitialized(context)
        scope.launch { engine?.warmUp() }
    }

    fun engine(context: Context): VoiceCorrectionEngine? {
        ensureInitialized(context)
        return engine
    }

    fun buildConversationContext(
        context: Context,
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
        screen: ScreenContextBuilder.Snapshot? = null,
    ): ConversationContext {
        ensureInitialized(context)
        val seg = segment ?: return ConversationContext.EMPTY
        val rnk = rank ?: return ConversationContext.EMPTY

        val screenContext = runCatching {
            ScreenContextBuilder.build(screen, latestReply(messages))
        }.onFailure { Log.w(TAG, "screen context failed", it) }.getOrNull()
        screenContext?.let { logScreenContext(it) }

        val source = toSourceMessages(messages)
        if (source.isEmpty()) {
            return if (screenContext == null) ConversationContext.EMPTY
            else ConversationContext(screen = screenContext)
        }
        return CorrectionContextBuilder.build(source, seg, rnk).copy(screen = screenContext)
    }

    internal fun latestReply(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): ScreenContextBuilder.LatestReply? {
        for (msg in messages.asReversed()) {
            if (msg.role != "assistant") continue
            var text = msg.content
            if (msg.toolBlocks.isNotEmpty()) {
                val blockText = msg.toolBlocks.filter { it.isText }.joinToString("\n") { it.content }
                if (blockText.isNotBlank()) text = blockText
            }
            val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text).trim()
            if (stripped.isNotEmpty()) return ScreenContextBuilder.LatestReply(msg.id, stripped)
        }
        return null
    }

    private fun logScreenContext(screen: ScreenContext) {
        if (!com.openminis.app.BuildConfig.DEBUG) return
        fun chunked(prefix: String, text: String) {
            text.chunked(800).forEachIndexed { i, part ->
                Log.d("CorrectionContext", "$prefix${if (i > 0) " (cont.)" else ""} $part")
            }
        }
        screen.viewportLines.forEachIndexed { i, line ->
            chunked("[Screen:viewport] #${i + 1}/${screen.viewportLines.size} len=${line.length}", line)
        }
        screen.latestReply?.let {
            chunked("[Screen:latest] onScreen=${screen.latestReplyOnScreen} len=${it.length}", it)
        }
    }

    internal fun toSourceMessages(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): List<CorrectionSourceMessage> = messages.mapNotNull { msg ->
        val role = when (msg.role) {
            "user" -> CorrectionSourceMessage.Role.USER
            "assistant" -> CorrectionSourceMessage.Role.ASSISTANT
            else -> return@mapNotNull null
        }
        var text = msg.content
        if (role == CorrectionSourceMessage.Role.ASSISTANT && msg.toolBlocks.isNotEmpty()) {
            val blockText = msg.toolBlocks
                .filter { it.isText }
                .joinToString("\n") { it.content }
            if (blockText.isNotBlank()) text = blockText
        }
        val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text)
        if (stripped.isBlank()) null else CorrectionSourceMessage(role, stripped)
    }


    fun captureTranscriptEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTranscriptEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "transcript capture failed", it) }
        }
    }

    fun captureTextInputEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTextInputEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "text-input capture failed", it) }
        }
    }

    fun captureSuggestionAccepted(
        context: Context,
        original: String,
        corrected: String,
        summary: String,
    ) {
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordSuggestionAccepted(original, corrected, summary) }
                .onFailure { Log.w(TAG, "accept capture failed", it) }
        }
    }

    fun mineVocabularyIfNeeded(context: Context) {
        ensureInitialized(context)
        val b = vocabularyBuilder ?: return
        scope.launch {
            runCatching { b.buildIfNeeded() }
                .onFailure { Log.w(TAG, "vocabulary build failed", it) }
        }
    }

    fun clearAllData(context: Context) {
        ensureInitialized(context)
        scope.launch {
            db?.clearAll()
            engine?.clearCache()
        }
    }

    suspend fun counts(context: Context): Map<String, Int> {
        ensureInitialized(context)
        return db?.counts() ?: emptyMap()
    }
}
