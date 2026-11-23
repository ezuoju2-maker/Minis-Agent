package com.openminis.app.ui

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionMounts
import okio.buffer
import okio.source
import java.io.File

class MinisImageFetcher(
    private val uri: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val sessionId = sessionOf(uri, options.parameters.value<String>(SESSION_PARAM))
        val hostFile = resolve(options.context, uri, sessionId)
        if (hostFile == null || !hostFile.isFile) {
            throw java.io.FileNotFoundException("File not found: $uri (session=${sessionId ?: "-"})")
        }

        return SourceResult(
            source = coil.decode.ImageSource(
                source = hostFile.source().buffer(),
                context = options.context,
            ),
            mimeType = guessMimeType(hostFile),
            dataSource = DataSource.DISK,
        )
    }

    private fun guessMimeType(file: File): String? = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> null
    }

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.startsWith("minis://")) return null
            return MinisImageFetcher(data, options)
        }
    }

    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "minis") return null
            return MinisImageFetcher(data.toString(), options)
        }
    }

    class MtimeKeyer : Keyer<Uri> {
        override fun key(data: Uri, options: Options): String? {
            if (data.scheme != "minis") return null
            return composeMtimeKey(options.context, data.toString(), options.parameters.value(SESSION_PARAM))
        }
    }

    class StringMtimeKeyer : Keyer<String> {
        override fun key(data: String, options: Options): String? {
            if (!data.startsWith("minis://")) return null
            return composeMtimeKey(options.context, data, options.parameters.value(SESSION_PARAM))
        }
    }

    companion object {
        private const val TAG = "MinisImageFetcher"

        const val SESSION_PARAM = "minis.sessionId"

        private fun composeMtimeKey(context: Context, uri: String, paramSession: String?): String {
            val sessionId = sessionOf(uri, paramSession)
            val mtime = try {
                resolve(context, uri, sessionId)?.lastModified() ?: 0L
            } catch (_: Throwable) {
                0L
            }
            return if (sessionId != null && paramSession != null && sessionOf(uri, null) == null) {
                "$uri?mt=$mtime&s=$sessionId"
            } else {
                "$uri?mt=$mtime"
            }
        }

        internal fun sessionOf(uri: String, paramSession: String?): String? {
            val query = uri.substringAfter('?', "")
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    val k = pair.substringBefore('=')
                    if (k == "session" || k == "sessionId") {
                        val v = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                            .getOrNull()
                        if (!v.isNullOrBlank() && isSafeSessionId(v)) return v
                    }
                }
            }
            return paramSession?.takeIf { it.isNotBlank() && isSafeSessionId(it) }
        }

        private fun isSafeSessionId(id: String): Boolean =
            id != "." && id != ".." && '/' !in id && '\\' !in id

        internal fun resolve(context: Context, uri: String, sessionId: String?): java.io.File? =
            resolve(context.filesDir, uri, sessionId)

        internal fun resolve(filesDir: java.io.File, uri: String, sessionId: String?): java.io.File? {
            val stripped = uri.removePrefix("minis://").substringBefore('?')
            val decoded = try {
                java.net.URLDecoder.decode(stripped, "UTF-8")
            } catch (_: Throwable) {
                stripped
            }
            if (decoded.split('/').any { it == ".." }) return null
            val linuxPath = "/var/minis/$decoded"
            val sub = decoded.substringBefore('/')
            val rest = decoded.substringAfter('/', "")

            if (PRootKernel.isPerSessionPath(linuxPath)) {
                if (sessionId != null) {
                    val direct = SessionMounts.sessionDir(filesDir, sessionId, sub)
                        .let { if (rest.isEmpty()) it else java.io.File(it, rest) }
                    if (direct.isFile) return direct
                }
                PRootKernel.resolveHostPath(linuxPath)?.takeIf { it.isFile }?.let { return it }
                return searchSessions(filesDir, sub, rest, exclude = sessionId)
            }
            if (sub in SessionMounts.GLOBAL_SUBDIRS) {
                val f = java.io.File(filesDir, "minis-global/$decoded")
                if (f.isFile) return f
            }
            return PRootKernel.resolveHostPath(linuxPath)
        }

        private fun searchSessions(
            filesDir: java.io.File,
            sub: String,
            rest: String,
            exclude: String?,
        ): java.io.File? {
            if (rest.isEmpty()) return null
            val hit = java.io.File(filesDir, "minis-sessions").listFiles().orEmpty()
                .asSequence()
                .filter { it.name != exclude }
                .map { java.io.File(it, "$sub/$rest") }
                .filter { it.isFile }
                .maxByOrNull { it.lastModified() }
            if (hit != null) Log.i(TAG, "orphan link $sub/$rest found by search in ${hit.parentFile?.parentFile?.name}")
            return hit
        }
    }
}
