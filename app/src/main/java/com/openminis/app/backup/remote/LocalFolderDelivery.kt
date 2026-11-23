package com.openminis.app.backup.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.openminis.app.logging.AppLogger
import java.io.File

class LocalFolderDelivery(private val context: Context) {

    class DeliveryException(message: String) : Exception(message)

    fun deliver(
        packageFile: File,
        treeUri: String,
        isCancelled: () -> Boolean = { false },
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        if (!packageFile.exists()) throw DeliveryException("Couldn't read the backup file.")

        val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: throw DeliveryException("That folder is no longer available.")
        val parentDoc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrNull() ?: throw DeliveryException("That folder is no longer available.")

        val name = packageFile.name
        val partialName = "$name.$PARTIAL_SUFFIX"

        deleteChild(parentDoc, partialName)

        val partial = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, parentDoc, MIME_TYPE, partialName,
            )
        }.getOrNull() ?: throw DeliveryException(
            "Couldn't create a file in that folder — it may have been moved or removed.",
        )

        val total = packageFile.length()
        try {
            context.contentResolver.openOutputStream(partial, "w")?.use { out ->
                packageFile.inputStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = 0L
                    while (true) {
                        if (isCancelled()) throw DeliveryException("Delivery stopped.")
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                        onProgress?.invoke(sent, total)
                    }
                    out.flush()
                }
            } ?: throw DeliveryException("Couldn't open that folder for writing.")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw if (e is DeliveryException) e
            else DeliveryException(e.message ?: "Couldn't write to that folder.")
        }

        deleteChild(parentDoc, name)
        val renamed = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, partial, name)
        }.getOrNull()
        if (renamed == null) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw DeliveryException("Couldn't finish writing to that folder.")
        }

        AppLogger.info(TAG, "[LocalDest] wrote $name ($total bytes) to $treeUri")
    }

    fun delete(treeUri: String, name: String) {
        val tree = Uri.parse(treeUri)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc,
            DocumentsContract.getDocumentId(parentDoc),
        )
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("Could not open the folder to delete '$name'.")

        var target: Uri? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != name) continue
                target = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                break
            }
        }
        val doc = target ?: return
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, doc)
        if (!deleted) throw IllegalStateException("The folder refused to delete '$name'.")
    }

    private fun deleteChild(parentDoc: Uri, displayName: String) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDoc,
                DocumentsContract.getDocumentId(parentDoc),
            )
        }.getOrNull() ?: return

        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) != displayName) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                    DocumentsContract.deleteDocument(context.contentResolver, doc)
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val PARTIAL_SUFFIX = "partial"

        private const val MIME_TYPE = "application/octet-stream"
    }
}
