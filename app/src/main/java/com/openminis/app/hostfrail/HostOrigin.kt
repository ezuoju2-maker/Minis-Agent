package com.openminis.app.hostfrail

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object HostOrigin {

    const val EXPECTED_PACKAGE: String = "com.openminis.app"

    @Volatile
    private var cachedDigest: String? = null

    @Volatile
    private var cachedPackage: String? = null

    fun packageName(): String = cachedPackage ?: EXPECTED_PACKAGE

    fun packageInfo(context: Context): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    fun signaturesOf(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info28 = info.signingInfo ?: return emptyList()
            val primary = if (info28.hasMultipleSigners()) {
                info28.apkContentsSigners
            } else {
                info28.signingCertificateHistory
            }
            return primary?.toList() ?: emptyList()
        }
        @Suppress("DEPRECATION")
        return info.signatures?.toList() ?: emptyList()
    }

    fun signingDigest(context: Context): String? {
        cachedDigest?.let { return it }
        val info = packageInfo(context) ?: return null
        val sigs = signaturesOf(info)
        if (sigs.isEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        for (s in sigs) {
            val b = s.toByteArray()
            md.update(b.size.ushr(24).toByte())
            md.update(b.size.ushr(16).toByte())
            md.update(b.size.ushr(8).toByte())
            md.update(b.size.toByte())
            md.update(b)
        }
        val out = md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        cachedDigest = out
        cachedPackage = info.packageName
        return out
    }

    fun deriveMaterial(context: Context, purpose: String, length: Int = 32): ByteArray {
        val seed = signingDigest(context) ?: context.packageName
        val md = MessageDigest.getInstance("SHA-256")
        md.update("minis-host-origin/v1".toByteArray(StandardCharsets.UTF_8))
        md.update(seed.toByteArray(StandardCharsets.UTF_8))
        md.update(0.toByte())
        md.update(purpose.toByteArray(StandardCharsets.UTF_8))
        val first = md.digest()
        if (length <= first.size) return first.copyOf(length)
        val out = ByteArray(length)
        var filled = 0
        var counter = 0
        while (filled < length) {
            md.reset()
            md.update(first)
            md.update(counter.toByte())
            val block = md.digest()
            val n = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    fun retain(context: Context) {
        signingDigest(context)
    }
}
