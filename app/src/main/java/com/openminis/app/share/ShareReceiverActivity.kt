package com.openminis.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID

class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
        private const val INLINE_TEXT_LIMIT = 1000
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = mutableListOf<PendingShare.Item>()
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> handleSingleSend(intent, items)
                Intent.ACTION_SEND_MULTIPLE -> handleMultipleSend(intent, items)
                Intent.ACTION_VIEW -> handleView(intent, items)
                else -> AppLogger.warning(TAG, "unhandled action: ${intent?.action}")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "extraction failed: ${e.message}")
        }

        val providerJson = try {
            items.singleOrNull()?.let { providerExportJsonOrNull(it) }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "provider-JSON detection failed: ${t.message}")
            null
        }
        if (providerJson != null) {
            if (promptProviderImportOrAttach(providerJson, items)) return
        }

        finishWithAttachmentFlow(items)
    }

    private fun finishWithAttachmentFlow(items: List<PendingShare.Item>) {
        try {
            if (items.isNotEmpty()) {
                SharedShareStore.savePendingShare(
                    this,
                    PendingShare(items, System.currentTimeMillis()),
                )
            } else {
                AppLogger.info(TAG, "no shareable items extracted")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "savePendingShare failed: ${e.message}")
        }
        launchMainActivity()
        try {
            finish()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "finish() failed: ${t.message}")
        }
    }

    private fun providerExportJsonOrNull(item: PendingShare.Item): String? {
        if (item.kind != PendingShare.Item.Kind.ATTACHMENT) return null
        val name = item.value
        if (!name.endsWith(".json", ignoreCase = true)) return null
        val file = File(SharedShareStore.sharedFileDirectory(this), name)
        if (!file.isFile || file.length() > 1_000_000L) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "providerExportJsonOrNull read failed: ${e.message}")
            return null
        }
        val obj = try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val looksLikeProvider = obj.optString("providerType").isNotEmpty() &&
            obj.optString("credentialType").isNotEmpty() &&
            obj.optJSONArray("models") != null
        return if (looksLikeProvider) text else null
    }

    private fun promptProviderImportOrAttach(
        json: String,
        items: List<PendingShare.Item>,
    ): Boolean {
        return try {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(com.openminis.app.R.string.share_provider_json_title))
                .setMessage(getString(com.openminis.app.R.string.share_provider_json_message))
                .setPositiveButton(getString(com.openminis.app.R.string.share_provider_json_import)) { _, _ ->
                    try {
                        importProviderJson(json)
                    } catch (t: Throwable) {
                        AppLogger.error(TAG, "provider import failed: ${t.message}")
                    }
                    finish()
                }
                .setNegativeButton(getString(com.openminis.app.R.string.share_provider_json_attach)) { _, _ ->
                    finishWithAttachmentFlow(items)
                }
                .setOnCancelListener {
                    finishWithAttachmentFlow(items)
                }
                .show()
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "provider-import dialog could not be shown: ${t.message}")
            false
        }
    }

    private fun importProviderJson(json: String) {
        val repo = (applicationContext as? com.openminis.app.MinisApp)?.providerRepositoryOrNull
        if (repo == null) {
            AppLogger.warning(TAG, "providerRepository unavailable; cannot import")
            toast(getString(com.openminis.app.R.string.share_provider_json_import_failed))
            return
        }
        val label = repo.importInstanceJSON(json)
        if (label != null) {
            AppLogger.info(TAG, "imported provider \"$label\" from shared JSON")
            toast(getString(com.openminis.app.R.string.share_provider_json_imported, label))
        } else {
            AppLogger.warning(TAG, "importInstanceJSON returned null")
            toast(getString(com.openminis.app.R.string.share_provider_json_import_failed))
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun handleSingleSend(intent: Intent, out: MutableList<PendingShare.Item>) {
        val type = intent.type ?: ""
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        when (singleSendSource(type, hasText = text != null)) {
            SingleSendSource.TEXT -> {
                text ?: return
                if (text.length <= INLINE_TEXT_LIMIT) {
                    out += PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, text)
                } else {
                    val name = "shared-text-${shortId()}.txt"
                    File(SharedShareStore.sharedFileDirectory(this), name)
                        .writeText(text, Charsets.UTF_8)
                    out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
                }
            }
            SingleSendSource.STREAM -> {
                val uri = getParcelableExtra<Uri>(intent, Intent.EXTRA_STREAM) ?: return
                copyUriToStaging(uri, type)?.let {
                    out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
                }
            }
        }
    }

    private fun handleMultipleSend(intent: Intent, out: MutableList<PendingShare.Item>) {
        val type = intent.type ?: ""
        val uris = getParcelableArrayListExtra<Uri>(intent, Intent.EXTRA_STREAM) ?: return
        for (uri in uris) {
            copyUriToStaging(uri, type)?.let {
                out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
            }
        }
    }

    private fun handleView(intent: Intent, out: MutableList<PendingShare.Item>) {
        val uri = intent.data
        if (uri == null) {
            AppLogger.warning(TAG, "ACTION_VIEW with no data uri")
            return
        }
        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        AppLogger.info(TAG, "ACTION_VIEW uri=$uri type=$type")
        copyUriToStaging(uri, type)?.let {
            out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
        }
    }

    private fun copyUriToStaging(uri: Uri, mimeType: String): String? {
        val ext = guessExtension(mimeType, uri)
        val prefix = when {
            mimeType.startsWith("image/") -> "shared-image"
            mimeType.startsWith("video/") -> "shared-video"
            else -> "shared"
        }
        val name = "$prefix-${shortId()}${if (ext.isNotEmpty()) ".$ext" else ""}"
        val dest = File(SharedShareStore.sharedFileDirectory(this), name)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            name
        } catch (e: Exception) {
            AppLogger.warning(TAG, "copyUriToStaging($uri): ${e.message}")
            null
        }
    }

    private fun guessExtension(mimeType: String, uri: Uri): String {
        val mm = android.webkit.MimeTypeMap.getSingleton()
        val fromMime = mm.getExtensionFromMimeType(mimeType.substringBefore(';'))
        if (!fromMime.isNullOrBlank()) return fromMime
        return uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 1..6 } ?: ""
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    private fun launchMainActivity() {
        val outcome = ShareHandoffPolicy.handOff(
            primary = {
                startActivity(mainActivityIntent(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            },
            fallback = {
                startActivity(mainActivityIntent(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onError = { stage, t ->
                AppLogger.error(TAG, "$stage startActivity(MainActivity) failed: ${t.javaClass.simpleName}: ${t.message}")
            },
        )

        when (outcome) {
            ShareHandoffPolicy.Outcome.LAUNCHED -> Unit
            ShareHandoffPolicy.Outcome.LAUNCHED_VIA_FALLBACK ->
                AppLogger.info(TAG, "MainActivity launched via NEW_TASK-only fallback")
            ShareHandoffPolicy.Outcome.FAILED -> {
                try {
                    toast(getString(com.openminis.app.R.string.share_open_app_failed))
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "failure toast failed: ${t.message}")
                }
            }
        }
    }

    private fun mainActivityIntent(flags: Int): Intent =
        Intent(this, Class.forName("com.openminis.app.MainActivity")).apply {
            addFlags(flags)
            putExtra("shared_content", true)
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtra(
        intent: Intent, key: String,
    ): T? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableExtra(key, T::class.java)
    else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtra(
        intent: Intent, key: String,
    ): ArrayList<T>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableArrayListExtra(key, T::class.java)
    else intent.getParcelableArrayListExtra(key)
}

internal enum class SingleSendSource { TEXT, STREAM }

internal fun singleSendSource(type: String, hasText: Boolean): SingleSendSource =
    if (type == "text/plain" && hasText) SingleSendSource.TEXT else SingleSendSource.STREAM
