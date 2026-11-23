package com.openminis.app.backup

import com.openminis.app.logging.AppLogger
import java.io.File

class BackupPackageReader(private val root: File) {

    private val TAG = "Backup"

    val manifestFile = File(root, "manifest.json")

    fun manifestBytes(): ByteArray {
        if (!manifestFile.exists()) throw BackupException("Package has no manifest.json")
        return manifestFile.readBytes()
    }

    fun readManifest(): BackupManifest {
        val raw = manifestBytes()
        val manifest = try {
            BackupFormat.json.decodeFromString(
                BackupManifest.serializer(), raw.toString(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw BackupException("The backup's manifest could not be read.", e)
        }
        val major = manifest.format.substringBefore('/', "") +
            "/" + manifest.format.substringAfter('/', "").substringBefore('.')
        if (major != BackupFormat.CURRENT) {
            throw BackupException(
                "This backup was created by a newer version of Minis (${manifest.format}). Please update the app."
            )
        }
        return manifest
    }

    val isEncrypted: Boolean
        get() = runCatching { readManifest().encryption != null }.getOrDefault(false)

    fun assertNoUndeclaredEncryption(manifest: BackupManifest = readManifest()) {
        if (manifest.encryption != null) return
        if (hasEncryptedMembers()) {
            throw BackupException(
                "This package contains encrypted content but its manifest declares none — " +
                    "it may have been modified."
            )
        }
    }

    private fun hasEncryptedMembers(): Boolean =
        root.walkTopDown().any { it.isFile && it.extension == "enc" }

    fun unlock(passphrase: String, manifest: BackupManifest = readManifest()): BackupCrypto.Keys {
        val encryption = manifest.encryption
            ?: throw BackupException("This package is not encrypted.")
        if (encryption.scheme != BackupCrypto.SCHEME) {
            throw BackupException(
                "This backup uses an unsupported encryption scheme (${encryption.scheme}). Please update the app."
            )
        }
        val keys = BackupCrypto.deriveKeys(passphrase, encryption.kdf)
        if (!BackupCrypto.verifierMatches(encryption.verifier, keys)) {
            keys.destroy()
            throw BackupCrypto.WrongPassphraseException()
        }
        verifyManifestAuthenticity(manifest, keys)
        return keys
    }

    private fun verifyManifestAuthenticity(manifest: BackupManifest, keys: BackupCrypto.Keys) {
        val sidecar = File(root, "manifest.mac")
        if (sidecar.exists()) {
            BackupCrypto.verifyManifestMac(manifestBytes(), sidecar.readText(), keys.macKey)
            return
        }
        if (manifest.manifestMac != null) {
            AppLogger.info(
                TAG,
                "[Backup] package carries only the legacy embedded manifest_mac; " +
                    "skipping MAC check (integrity hashes still apply)"
            )
        }
    }

    fun verifyIntegrity(
        manifest: BackupManifest = readManifest(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val failures = mutableListOf<String>()
        val total = manifest.integrity.size
        var done = 0
        for ((path, expected) in manifest.integrity) {
            val file = File(root, path)
            if (!file.exists()) {
                failures.add(path)
            } else if (BackupBlobStore.sha256OfFile(file) != expected) {
                failures.add(path)
            }
            done += 1
            onProgress?.invoke(done, total)
        }
        if (failures.isNotEmpty()) {
            AppLogger.error(TAG, "[Backup] integrity check failed for ${failures.size} member(s)")
        }
        return failures
    }

    fun materialize(logicalPath: String, keys: BackupCrypto.Keys?, scratch: File): File? {
        val plain = File(root, logicalPath)
        if (plain.exists()) return plain

        val encrypted = File(root, "$logicalPath.enc")
        if (!encrypted.exists()) return null
        val derived = keys ?: throw BackupException(
            "This backup is encrypted — a passphrase is required to read $logicalPath."
        )
        val key = if (logicalPath == "secrets.json") derived.secretsKey else derived.dataKey
        val out = File(scratch, logicalPath).also { it.parentFile?.mkdirs() }
        BackupCrypto.decryptFile(encrypted, out, key, "$logicalPath.enc")
        return out
    }

    fun memberNames(): List<String> {
        val base = root.canonicalFile
        return base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(base).path.replace(File.separatorChar, '/').removeSuffix(".enc") }
            .toList()
    }
}
