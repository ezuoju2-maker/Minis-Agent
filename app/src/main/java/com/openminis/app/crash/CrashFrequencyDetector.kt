package com.openminis.app.crash

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.openminis.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CrashFrequencyDetector {
    private const val TAG = "CrashFreqDetector"
    private const val WINDOW_MS: Long = 60L * 60L * 1000L
    private const val THRESHOLD = 2

    private const val SUPPRESSED_THRESHOLD = 5

    private const val PREFS = "crash_freq_prefs"
    private const val KEY_DISMISSED_AT = "dismissed_at"
    private const val KEY_FORCE_HOME_UNTIL = "force_home_until"
    private const val FORCE_HOME_GRACE_MS: Long = 60L * 60L * 1000L
    private const val KEY_SUPPRESS_UNTIL = "suppress_until"
    private const val SUPPRESS_AFTER_DISMISS_MS: Long = 24L * 60L * 60L * 1000L

    @Volatile
    var pendingShareFiles: List<File>? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    @JvmStatic
    fun registerSafeModeClearedListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun fireSafeModeCleared() {
        listeners.toList().forEach { runCatching { it() } }
    }

    private fun setSafeMode(value: Boolean) {
        val was = _safeMode.getAndSet(value)
        if (was && !value) {
            android.util.Log.i(TAG, "safe-mode cleared, firing ${listeners.size} listener(s)")
            fireSafeModeCleared()
        }
    }

    private fun getDismissedAt(ctx: Context): Long =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED_AT, 0L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "getDismissedAt failed: ${t.message}")
            0L
        }

    private fun setDismissedAt(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DISMISSED_AT, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setDismissedAt failed: ${t.message}")
        }
    }

    private fun setSuppressUntil(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SUPPRESS_UNTIL, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setSuppressUntil failed: ${t.message}")
        }
    }

    private fun getSuppressUntil(ctx: Context): Long =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_SUPPRESS_UNTIL, 0L)
        } catch (t: Throwable) {
            0L
        }

    private fun setForceHomeUntil(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_FORCE_HOME_UNTIL, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setForceHomeUntil failed: ${t.message}")
        }
    }

    @JvmStatic
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val until = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_FORCE_HOME_UNTIL, 0L)
        } catch (t: Throwable) {
            return false
        }
        return until > 0 && System.currentTimeMillis() < until
    }

    private val _safeMode = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic
    fun isSafeMode(): Boolean = _safeMode.get()

    internal fun thresholdFor(now: Long, suppressUntil: Long): Int =
        if (suppressUntil > 0 && now < suppressUntil) SUPPRESSED_THRESHOLD else THRESHOLD

    internal fun tripsSafeMode(recent: Int, now: Long, suppressUntil: Long): Boolean =
        recent >= thresholdFor(now, suppressUntil)

    fun checkAtLaunch(app: Application) {
        try {
            val now0 = System.currentTimeMillis()
            val suppressUntil = getSuppressUntil(app)
            val threshold = thresholdFor(now0, suppressUntil)
            val logsDir = File(app.filesDir, "logs")
            if (!logsDir.isDirectory) {
                pendingShareFiles = null
                return
            }
            val all = logsDir.listFiles { f ->
                val n = f.name
                (n.startsWith("crash-") || n.startsWith("native-crash-")) && n.endsWith(".log")
            } ?: return
            val now = System.currentTimeMillis()
            val rawDismissedAt = getDismissedAt(app)
            val dismissedAt = if (rawDismissedAt > 0 && now - rawDismissedAt <= WINDOW_MS) {
                rawDismissedAt
            } else {
                0L
            }
            val recent = all.filter {
                val mt = it.lastModified()
                mt > dismissedAt && now - mt <= WINDOW_MS
            }
            android.util.Log.i(
                TAG,
                "checkAtLaunch: total=${all.size} recent=${recent.size} " +
                    "threshold=$threshold dismissedAt=$dismissedAt" +
                    if (threshold == SUPPRESSED_THRESHOLD) {
                        " (user dismissed; raised threshold, ${(suppressUntil - now0) / 60_000L} min of suppress left)"
                    } else "",
            )
            if (recent.size < threshold) {
                pendingShareFiles = null
                return
            }
            pendingShareFiles = recent.sortedByDescending { it.lastModified() }
            setSafeMode(true)
            setForceHomeUntil(app, now + FORCE_HOME_GRACE_MS)
            android.util.Log.w(
                TAG,
                "Recent crashes: ${recent.size} in last hour — safe-mode ON, " +
                    "force-home grace set, will prompt on next Activity resume",
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "checkAtLaunch failed: ${t.message}")
        }
    }

    fun maybeShowOnActivity(
        activity: Activity,
        onClosed: (() -> Unit)? = null,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) {
        val triggerFiles = pendingShareFiles ?: run {
            onClosed?.invoke()
            return
        }
        pendingShareFiles = null
        try {
            val dismissAtCheckpoint = System.currentTimeMillis()
            setDismissedAt(activity, dismissAtCheckpoint)

            val pickable = buildPickableEntries(activity, triggerFiles)
            showFilePickerDialog(
                activity = activity,
                entries = pickable,
                onContinue = { selected ->
                    if (selected.isEmpty()) {
                        finishClose(onClosed, dismissContext = activity)
                        return@showFilePickerDialog
                    }
                    showMethodPicker(activity, selected, onClosed, saveLauncher)
                },
                onCancel = {
                    finishClose(onClosed, dismissContext = activity)
                    android.util.Log.i(TAG, "file picker cancelled — 24h suppress applied")
                },
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "dialog failed: ${t.message}")
            setSafeMode(false)
            onClosed?.invoke()
        }
    }

    private data class PickableEntry(
        val file: File,
        val label: String,
        val section: Int,
        val defaultChecked: Boolean,
    )

    private const val PICK_CRASH_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    private const val PICK_CRASH_LIMIT: Int = 10
    private const val PICK_RUN_LOG_WINDOW_MS: Long = 3L * 24L * 60L * 60L * 1000L

    private fun buildPickableEntries(
        ctx: Context,
        triggerFiles: List<File>,
    ): List<PickableEntry> {
        val logsDir = File(ctx.filesDir, "logs")
        val now = System.currentTimeMillis()
        val triggerSet = triggerFiles.map { it.absolutePath }.toHashSet()

        val crashes = (logsDir.listFiles { f ->
            val n = f.name
            (n.startsWith("crash-") || n.startsWith("native-crash-")) && n.endsWith(".log")
        } ?: emptyArray<File>())
            .toList()
            .filter {
                it.absolutePath in triggerSet || now - it.lastModified() <= PICK_CRASH_WINDOW_MS
            }
            .sortedByDescending { it.lastModified() }
            .take(PICK_CRASH_LIMIT)

        val dailyLogs = (logsDir.listFiles { f ->
            f.name.startsWith("minis-") && f.name.endsWith(".log")
        } ?: emptyArray<File>())
            .toList()
            .filter { now - it.lastModified() <= PICK_RUN_LOG_WINDOW_MS }
            .sortedByDescending { it.lastModified() }

        val out = mutableListOf<PickableEntry>()
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        for (f in crashes) {
            out += PickableEntry(
                file = f,
                label = "${f.name}  ·  ${formatBytes(f.length())}  ·  ${timeFmt.format(Date(f.lastModified()))}",
                section = 0,
                defaultChecked = true,
            )
        }
        for (f in dailyLogs) {
            out += PickableEntry(
                file = f,
                label = "${f.name}  ·  ${formatBytes(f.length())}",
                section = 1,
                defaultChecked = false,
            )
        }
        return out
    }

    private fun showFilePickerDialog(
        activity: Activity,
        entries: List<PickableEntry>,
        onContinue: (List<File>) -> Unit,
        onCancel: () -> Unit,
    ) {
        val checkBoxes = ArrayList<CheckBox>(entries.size)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        var lastSection = -1
        entries.forEachIndexed { idx, e ->
            if (e.section != lastSection) {
                lastSection = e.section
                val header = TextView(activity).apply {
                    text = activity.getString(
                        when (e.section) {
                            0 -> R.string.crash_freq_pick_section_crashes
                            else -> R.string.crash_freq_pick_section_logs
                        }
                    )
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, if (idx == 0) 0 else dp(12), 0, dp(4))
                    alpha = 0.6f
                }
                column.addView(header)
            }
            val cb = CheckBox(activity).apply {
                text = e.label
                isChecked = e.defaultChecked
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            checkBoxes.add(cb)
            column.addView(cb)
        }

        val scroll = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(column)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.crash_freq_pick_files_title)
            .setView(scroll)
            .setPositiveButton(R.string.crash_freq_pick_continue) { _, _ ->
                val picked = entries
                    .mapIndexedNotNull { i, e -> if (checkBoxes[i].isChecked) e.file else null }
                onContinue(picked)
            }
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        applyRoundedBackground(activity, dialog)
        dialog.show()
    }

    private fun applyRoundedBackground(activity: Activity, dialog: AlertDialog) {
        try {
            val density = activity.resources.displayMetrics.density
            val bgColor = run {
                val tv = android.util.TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                    tv.data
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(bgColor)
            }
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(bg)
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "applyRoundedBackground failed: ${t.message}")
        }
    }

    private fun showMethodPicker(
        activity: Activity,
        files: List<File>,
        onClosed: (() -> Unit)?,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val dialogRef = arrayOfNulls<AlertDialog>(1)

        fun addRow(textRes: Int, action: () -> Unit) {
            val tv = TextView(activity).apply {
                text = activity.getString(textRes)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                isFocusable = true
                background = androidx.core.content.ContextCompat
                    .getDrawable(activity, android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialogRef[0]?.dismiss()
                    action()
                }
            }
            column.addView(tv)
        }

        addRow(R.string.crash_freq_method_email) {
            dispatchZippedShare(activity, files, emailOnly = true, onClosed)
        }
        addRow(R.string.crash_freq_method_share) {
            dispatchZippedShare(activity, files, emailOnly = false, onClosed)
        }
        addRow(R.string.crash_freq_method_save) {
            if (saveLauncher != null) {
                dispatchZippedSaveViaPicker(activity, files, saveLauncher, onClosed)
            } else {
                dispatchZippedSave(activity, files, onClosed)
            }
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.crash_freq_method_title, files.size))
            .setView(column)
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ ->
                finishClose(onClosed, dismissContext = activity)
            }
            .setOnCancelListener { finishClose(onClosed, dismissContext = activity) }
            .create()
        applyRoundedBackground(activity, dlg)
        dialogRef[0] = dlg
        dlg.show()
    }

    private fun finishClose(onClosed: (() -> Unit)?, dismissContext: Context? = null) {
        if (dismissContext != null) {
            val until = System.currentTimeMillis() + SUPPRESS_AFTER_DISMISS_MS
            setSuppressUntil(dismissContext, until)
            android.util.Log.i(
                TAG,
                "user dismissed — suppress next 24h " +
                    "(until ts=$until)",
            )
        }
        setSafeMode(false)
        android.util.Log.i(TAG, "safe-mode OFF (flow finished)")
        onClosed?.invoke()
    }

    private fun dispatchZippedShare(
        ctx: Context,
        files: List<File>,
        emailOnly: Boolean,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            val authority = "${ctx.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, zip)
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val subject = ctx.getString(R.string.crash_freq_email_subject, date)
            val recipient = "dev@openminis.app"

            val launched: Boolean = if (emailOnly) {
                tryLaunchMailto(ctx, recipient, subject, uri)
            } else {
                tryLaunchShare(ctx, uri, subject)
            }
            if (!launched) {
                tryLaunchShare(ctx, uri, subject)
            }
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ finishClose(onClosed) }, 500L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share dispatch failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_zip_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            finishClose(onClosed)
        }
    }

    private fun tryLaunchMailto(
        ctx: Context,
        recipient: String,
        subject: String,
        attachment: Uri,
    ): Boolean {
        return try {
            val body = ctx.getString(R.string.crash_freq_email_body)
            val selector = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                this.selector = selector
            }
            if (selector.resolveActivity(ctx.packageManager) == null) {
                android.util.Log.w(TAG, "mailto selector: no email app resolved")
                return false
            }
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "mailto launch failed: ${t.message}")
            false
        }
    }

    private fun tryLaunchShare(
        ctx: Context,
        attachment: Uri,
        subject: String,
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, attachment)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                ctx.getString(R.string.crash_freq_share_chooser),
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share launch failed: ${t.message}")
            false
        }
    }

    private fun dispatchZippedSaveViaPicker(
        ctx: Context,
        files: List<File>,
        saveLauncher: (zip: File, onSaveDone: () -> Unit) -> Unit,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            saveLauncher(zip) { finishClose(onClosed) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save (picker) failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            finishClose(onClosed)
        }
    }

    private fun dispatchZippedSave(
        ctx: Context,
        files: List<File>,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            val savedPath: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                resolver.openOutputStream(target).use { out ->
                    requireNotNull(out) { "MediaStore openOutputStream returned null" }
                    FileInputStream(zip).use { input -> input.copyTo(out) }
                }
                "Downloads/${zip.name}"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                downloads.mkdirs()
                val dest = File(downloads, zip.name)
                FileInputStream(zip).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest.absolutePath
            }
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_success, savedPath),
                Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            finishClose(onClosed)
        }
    }

    private fun packageZip(ctx: Context, files: List<File>): File? {
        val readable = files.filter { it.exists() && it.length() > 0 }
        if (readable.isEmpty()) return null
        val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(shareDir, "minis-logs-$stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zout ->
            val buf = ByteArray(64 * 1024)
            for (f in readable) {
                try {
                    zout.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { fin ->
                        while (true) {
                            val n = fin.read(buf)
                            if (n <= 0) break
                            zout.write(buf, 0, n)
                        }
                    }
                    zout.closeEntry()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "zip skip ${f.name}: ${t.message}")
                }
            }
        }
        return zipFile
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}
