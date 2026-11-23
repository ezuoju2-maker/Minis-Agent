package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"
    internal const val FALLBACK_SUFFIX = "_plain_fallback"

    private val lock = Any()
    private val cache = HashMap<String, SharedPreferences>()

    fun safeCreate(context: Context, fileName: String): SharedPreferences =
        cached(fileName) { open(AndroidOps(context.applicationContext), fileName) }

    internal fun cached(fileName: String, create: () -> SharedPreferences): SharedPreferences =
        synchronized(lock) { cache.getOrPut(fileName, create) }

    internal fun resetCacheForTest() = synchronized(lock) { cache.clear() }

    internal interface Ops {
        fun build(fileName: String): SharedPreferences
        fun masterKeyWorks(): Boolean
        fun discardStore(fileName: String)
        fun deleteMasterKey()
        fun plainFallback(fileName: String): SharedPreferences
        fun adoptFallback(fileName: String, into: SharedPreferences)
        fun log(priority: Int, msg: String, t: Throwable? = null)
    }

    internal fun open(ops: Ops, fileName: String): SharedPreferences {
        fun attempt(label: String, priority: Int = Log.WARN): SharedPreferences? = try {
            ops.build(fileName)
        } catch (t: Throwable) {
            ops.log(priority, "$label($fileName) failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
        fun ready(p: SharedPreferences) = p.also { ops.adoptFallback(fileName, it) }

        attempt("first create")?.let { return ready(it) }
        attempt("retry create")?.let { return ready(it) }

        if (ops.masterKeyWorks()) {
            ops.log(Log.WARN, "master key OK - discarding only $fileName (its keyset was wrapped by another key)")
            ops.discardStore(fileName)
        } else {
            ops.log(Log.ERROR, "master key unusable - recreating it; discarding $fileName")
            ops.deleteMasterKey()
            ops.discardStore(fileName)
        }

        attempt("rebuild", Log.ERROR)?.let { return ready(it) }

        ops.log(Log.WARN, "falling back to plain SharedPreferences for $fileName - credentials lost")
        return ops.plainFallback(fileName)
    }

    internal fun moveEntries(plain: SharedPreferences, into: SharedPreferences): Int {
        val all = plain.all
        if (all.isEmpty()) return 0
        val ed = into.edit()
        for ((k, v) in all) {
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> ed.putString(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Set<*> -> ed.putStringSet(k, v as Set<String>)
            }
        }
        if (!ed.commit()) return 0
        plain.edit().clear().commit()
        return all.size
    }

    private class AndroidOps(private val context: Context) : Ops {
        override fun build(fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override fun masterKeyWorks(): Boolean = (1..2).any { roundTrip() }

        private fun roundTrip(): Boolean = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
                ?: return@runCatching true
            val probe = "minis-keystore-probe".toByteArray()
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = enc.doFinal(probe)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv)) }
            dec.doFinal(sealed).contentEquals(probe)
        }.onFailure { log(Log.WARN, "master key probe failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)

        override fun discardStore(fileName: String) {
            runCatching { context.deleteSharedPreferences(fileName) }
                .onFailure { log(Log.WARN, "discard $fileName failed: ${it.message}") }
        }

        override fun deleteMasterKey() {
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }.onFailure { log(Log.WARN, "delete master key failed: ${it.message}") }
        }

        override fun plainFallback(fileName: String): SharedPreferences =
            context.getSharedPreferences(fileName + FALLBACK_SUFFIX, Context.MODE_PRIVATE)

        override fun adoptFallback(fileName: String, into: SharedPreferences) {
            runCatching {
                val name = fileName + FALLBACK_SUFFIX
                val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                if (!java.io.File(dir, "$name.xml").exists()) return
                val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val moved = moveEntries(plain, into)
                if (plain.all.isEmpty()) context.deleteSharedPreferences(name)
                log(Log.INFO, "moved $moved entr(ies) from $name back into encrypted $fileName")
            }.onFailure { log(Log.WARN, "adopt fallback for $fileName failed: ${it.message}") }
        }

        override fun log(priority: Int, msg: String, t: Throwable?) {
            if (t != null && priority >= Log.ERROR) Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(t))
            else Log.println(priority, TAG, msg)
        }
    }
}
