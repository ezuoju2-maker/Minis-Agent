package com.openminis.app.crash

import android.util.Log
import java.io.File

object NativeCrashHandler {

    private const val TAG = "NativeCrashHandler"
    @Volatile private var installed = false

    fun install(logsDir: File) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                System.loadLibrary("minis_crash_handler")
            } catch (t: Throwable) {
                Log.w(TAG, "loadLibrary failed: ${t.message}")
                return
            }
            try {
                logsDir.mkdirs()
                nativeInstall(logsDir.absolutePath)
                installed = true
                Log.i(TAG, "installed; dir=${logsDir.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "nativeInstall failed: ${t.message}")
            }
        }
    }

    private external fun nativeInstall(logDir: String)
}
