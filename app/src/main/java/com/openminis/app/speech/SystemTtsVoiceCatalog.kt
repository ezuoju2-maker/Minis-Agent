package com.openminis.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

object SystemTtsVoiceCatalog {

    data class SystemVoice(
        val name: String,
        val label: String,
        val locale: Locale,
        val networkRequired: Boolean,
    )

    private const val TAG = "SystemTtsVoiceCatalog"
    private const val INIT_TIMEOUT_MS = 4_000L

    @Volatile
    private var cache: List<SystemVoice>? = null
    private val lock = Mutex()

    suspend fun voices(context: Context): List<SystemVoice> {
        cache?.let { return it }
        return lock.withLock {
            cache?.let { return it }
            val listed = enumerate(context.applicationContext)
            if (listed.isNotEmpty()) cache = listed
            listed
        }
    }

    private suspend fun enumerate(context: Context): List<SystemVoice> {
        val ready = CompletableDeferred<Boolean>()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = kotlinx.coroutines.withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        val raw: Set<Voice> = if (ok) {
            runCatching { tts.voices ?: emptySet() }.getOrElse { emptySet() }
        } else emptySet()
        runCatching { tts.shutdown() }
        if (raw.isEmpty()) {
            AppLogger.info(TAG, "no voices enumerated (initOk=$ok)")
            return emptyList()
        }

        val uiLocale = Locale.getDefault()
        val mapped = raw
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .map { v ->
                val variant = v.name.substringAfterLast("-x-", "")
                    .substringBefore("-").ifBlank { null }
                val net = v.isNetworkConnectionRequired
                val base = v.locale.getDisplayName(uiLocale).ifBlank { v.locale.toLanguageTag() }
                val label = buildString {
                    append(base)
                    if (variant != null) append(" · ").append(variant)
                    if (net) append(" ☁")
                }
                SystemVoice(name = v.name, label = label, locale = v.locale, networkRequired = net)
            }
            .sortedWith(
                compareBy(
                    { tier(it.locale, uiLocale) },
                    { it.locale.toLanguageTag() },
                    { it.label },
                ),
            )
        AppLogger.info(TAG, "enumerated ${mapped.size} voices (raw=${raw.size})")
        return mapped
    }

    private fun tier(l: Locale, ui: Locale): Int = when {
        l.language == ui.language -> 0
        l.language == "zh" || l.language == "en" -> 1
        else -> 2
    }
}
