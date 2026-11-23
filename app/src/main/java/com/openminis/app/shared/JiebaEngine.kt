package com.openminis.app.shared

import android.content.Context
import java.io.File

internal class JiebaEngine(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var initialized = false

    val isReady: Boolean get() = initialized

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        return try {
            val dictDir = extractDictionaries()
            initialized = nativeInit(dictDir.absolutePath)
            initialized
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Jieba init failed", t)
            false
        }
    }

    fun segment(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return nativeSegment(text).asList()
    }

    fun segmentForSearch(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return nativeSegmentForSearch(text).asList()
    }

    fun posTag(text: String): List<Pair<String, String>> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        val flat = nativePosTag(text)
        val out = ArrayList<Pair<String, String>>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            out.add(flat[i] to flat[i + 1])
            i += 2
        }
        return out
    }

    private fun extractDictionaries(): File {
        val targetDir = File(appContext.filesDir, "jieba")
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val extractedVersion = prefs.getInt(KEY_DICT_VERSION, -1)

        val allPresent = DICT_FILES.all { File(targetDir, it).exists() }
        if (extractedVersion == DICT_VERSION && allPresent) {
            return targetDir
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw java.io.IOException("Cannot create dict dir: $targetDir")
        }
        for (name in DICT_FILES) {
            copyAsset("jieba/$name", File(targetDir, name))
        }
        prefs.edit().putInt(KEY_DICT_VERSION, DICT_VERSION).apply()
        return targetDir
    }

    private fun copyAsset(assetPath: String, dest: File) {
        appContext.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }

    private external fun nativeInit(dictDir: String): Boolean
    private external fun nativeSegment(text: String): Array<String>
    private external fun nativeSegmentForSearch(text: String): Array<String>
    private external fun nativePosTag(text: String): Array<String>

    companion object {
        private const val TAG = "JiebaEngine"
        private const val PREFS_NAME = "text_segmenter"
        private const val KEY_DICT_VERSION = "jieba_dict_version"

        private const val DICT_VERSION = 1

        private val DICT_FILES = listOf("jieba.dict.utf8", "hmm_model.utf8")

        init {
            System.loadLibrary("jieba_jni")
        }
    }
}
