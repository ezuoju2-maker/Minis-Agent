package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BackupPackageReaderTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("minisbak-reader", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeManifest(json: String) = File(root, "manifest.json").apply { writeText(json) }

    @Test
    fun `refuses a future format major version instead of guessing`() {
        writeManifest("""{"format":"minisbak/2","backup_id":"future"}""")
        var message: String? = null
        try {
            BackupPackageReader(root).readManifest()
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("must tell the user to update, got: $message", message?.contains("update") == true)
    }

    @Test
    fun `accepts a same-major minor bump`() {
        writeManifest("""{"format":"minisbak/1.4","backup_id":"newer-minor"}""")
        assertEquals("newer-minor", BackupPackageReader(root).readManifest().backupId)
    }

    @Test
    fun `integrity failures are listed, not thrown, so a partial restore stays possible`() {
        File(root, "data").mkdirs()
        File(root, "data/good.jsonl").writeText("good")
        File(root, "data/bad.jsonl").writeText("tampered")
        val goodSha = BackupBlobStore.sha256OfFile(File(root, "data/good.jsonl"))

        writeManifest(
            """
            {"format":"minisbak/1","integrity":{
              "data/good.jsonl":"$goodSha",
              "data/bad.jsonl":"0000000000000000000000000000000000000000000000000000000000000000",
              "data/missing.jsonl":"1111111111111111111111111111111111111111111111111111111111111111"
            }}
            """.trimIndent()
        )
        val failures = BackupPackageReader(root).verifyIntegrity()
        assertEquals(setOf("data/bad.jsonl", "data/missing.jsonl"), failures.toSet())
    }

    @Test
    fun `a wrong passphrase fails against the verifier before touching payload`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val right = BackupCrypto.deriveKeys("right passphrase", kdf)
        writeManifest(
            """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw==","iterations":1000},
             "verifier":"${right.verifier}"}}
            """.trimIndent()
        )

        val reader = BackupPackageReader(root)
        var threw = false
        try {
            reader.unlock("wrong passphrase")
        } catch (e: BackupCrypto.WrongPassphraseException) {
            threw = true
        }
        assertTrue("a wrong passphrase must be rejected by the verifier", threw)

        val keys = reader.unlock("right passphrase")
        assertEquals(right.verifier, keys.verifier)
    }

    @Test
    fun `an unsupported encryption scheme is refused`() {
        writeManifest(
            """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/99",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw=="},"verifier":"x"}}
            """.trimIndent()
        )
        var message: String? = null
        try {
            BackupPackageReader(root).unlock("whatever")
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("got: $message", message?.contains("unsupported encryption scheme") == true)
    }

    @Test
    fun `an edited manifest fails the sidecar MAC`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        val body = """
            {"format":"minisbak/1","encryption":{"scheme":"minisbak-enc/1",
             "kdf":{"alg":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw==","iterations":1000},
             "verifier":"${keys.verifier}"}}
        """.trimIndent()
        writeManifest(body)
        File(root, "manifest.mac").writeText(
            BackupCrypto.manifestMac(body.toByteArray(Charsets.UTF_8), keys.macKey)
        )
        writeManifest(body.replace("minisbak/1\"", "minisbak/1\",\"device_name\":\"forged\""))

        var threw = false
        try {
            BackupPackageReader(root).unlock("pass")
        } catch (e: BackupCrypto.ManifestTamperedException) {
            threw = true
        }
        assertTrue("an edited manifest must fail authentication", threw)
    }

    @Test
    fun `a stripped encryption block is caught when encrypted members remain`() {
        writeManifest("""{"format":"minisbak/1"}""")
        File(root, "data").mkdirs()
        File(root, "data/sessions.jsonl.enc").writeBytes(BackupCrypto.MAGIC + ByteArray(16))

        var message: String? = null
        try {
            BackupPackageReader(root).assertNoUndeclaredEncryption()
        } catch (e: BackupException) {
            message = e.message
        }
        assertTrue("got: $message", message?.contains("declares none") == true)
    }

    @Test
    fun `a genuinely unencrypted package passes the downgrade guard`() {
        writeManifest("""{"format":"minisbak/1"}""")
        File(root, "data").mkdirs()
        File(root, "data/sessions.jsonl").writeText("{}")
        BackupPackageReader(root).assertNoUndeclaredEncryption()
    }

    @Test
    fun `materialize returns null for a member the package does not carry`() {
        writeManifest("""{"format":"minisbak/1"}""")
        val scratch = File(root, "scratch").apply { mkdirs() }
        assertNull(BackupPackageReader(root).materialize("data/absent.jsonl", null, scratch))
    }

    @Test
    fun `materialize decrypts an encrypted member and leaves plaintext ones alone`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        writeManifest("""{"format":"minisbak/1"}""")

        File(root, "data").mkdirs()
        File(root, "data/plain.jsonl").writeText("plain content")
        val scratch = File(root, "scratch").apply { mkdirs() }
        val reader = BackupPackageReader(root)
        assertEquals(
            "plain content",
            reader.materialize("data/plain.jsonl", keys, scratch)?.readText(),
        )

        val source = File(root, "src.tmp").apply { writeText("secret content") }
        BackupCrypto.encryptFile(
            source, File(root, "data/enc.jsonl.enc"), keys.dataKey, "data/enc.jsonl.enc"
        )
        assertEquals(
            "secret content",
            reader.materialize("data/enc.jsonl", keys, scratch)?.readText(),
        )
    }

    @Test
    fun `secrets use the secrets subkey, not the data key`() {
        val kdf = BackupManifest.Encryption.KDF(
            alg = "pbkdf2-hmac-sha256", salt = "AAECAwQFBgcICQoLDA0ODw==", iterations = 1000
        )
        val keys = BackupCrypto.deriveKeys("pass", kdf)
        writeManifest("""{"format":"minisbak/1"}""")

        val source = File(root, "s.tmp").apply { writeText("""{"v":1,"providers":[]}""") }
        BackupCrypto.encryptFile(
            source, File(root, "secrets.json.enc"), keys.secretsKey, "secrets.json.enc"
        )
        val scratch = File(root, "scratch").apply { mkdirs() }
        assertEquals(
            """{"v":1,"providers":[]}""",
            BackupPackageReader(root).materialize("secrets.json", keys, scratch)?.readText(),
        )
    }
}
