package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger

object DatabaseVersionGuard {

    private const val TAG = "DbVersionGuard"

    const val CODE_DB_VERSION = 14

    private const val DB_NAME = "minis.db"

    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    fun isFromNewerBuild(context: Context): Boolean {
        val onDisk = readOnDiskVersion(context) ?: return false
        val newer = onDisk > CODE_DB_VERSION
        if (newer) {
            AppLogger.warning(
                TAG,
                "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION",
            )
        }
        return newer
    }

    fun isHandledDowngrade(onDiskVersion: Int): Boolean =
        (onDiskVersion == 12 && CODE_DB_VERSION == 11) ||
            (onDiskVersion == 13 && CODE_DB_VERSION == 12)

    enum class Decision {
        PROCEED,

        SHOW_NEWER_DB_GUIDANCE,
    }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        if (isHandledDowngrade(onDisk)) return Decision.PROCEED
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }
}
