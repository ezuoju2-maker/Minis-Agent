package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagementScreen(
    onBack: () -> Unit,
    onLogFileClick: (fileName: String) -> Unit = {},
    onConfigAuditEntryClick: (auditId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val dailyLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    val crashLogs = remember { mutableStateListOf<AppLogger.LogFileMeta>() }
    var totalSize by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }
    var loggingVerbose by remember { mutableStateOf(AppLogger.isVerbose(context)) }

    val initialTab = remember {
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingLogsTab() ?: "logs"
    }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTab) }

    val refreshTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTrigger.value) {
        loading = true
        val (daily, crash, total) = withContext(Dispatchers.IO) {
            val d = AppLogger.listLogFileMetas(prefix = "minis-", limit = 100)
            val cJ = AppLogger.listLogFileMetas(prefix = "crash-", limit = 100)
            val cN = AppLogger.listLogFileMetas(prefix = "native-crash-", limit = 100)
            val merged = (cJ + cN).sortedByDescending { it.name }.take(100)
            val sum = (d.sumOf { it.sizeBytes }) + (merged.sumOf { it.sizeBytes })
            Triple(d, merged, sum)
        }
        dailyLogs.clear(); dailyLogs.addAll(daily)
        crashLogs.clear(); crashLogs.addAll(crash)
        totalSize = total
        loading = false
    }
    fun refresh() { refreshTrigger.value++ }

    SettingsScaffold(
        title = stringResource(R.string.log_title),
        onBack = onBack,
        scrollable = false,
        actions = {
            TextButton(onClick = { shareTraceExport(context, otlp = true) }) {
                Text(stringResource(R.string.log_share_otlp), style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { shareTraceExport(context) }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.common_share),
                )
            }
        },
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == "logs",
                onClick = { tab = "logs" },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.log_title)) }
            SegmentedButton(
                selected = tab == "config-audit",
                onClick = { tab = "config-audit" },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.logs_tab_config_changes)) }
        }

        if (tab == "config-audit") {
            ConfigAuditScreen(modifier = Modifier.fillMaxSize(), onOpenEntry = onConfigAuditEntryClick)
        } else {
            LogsBody(
                context = context,
                dailyLogs = dailyLogs,
                crashLogs = crashLogs,
                totalSize = totalSize,
                loading = loading,
                loggingEnabled = loggingEnabled,
                onToggleLogging = {
                    loggingEnabled = it
                    AppLogger.setEnabled(context, it)
                },
                loggingVerbose = loggingVerbose,
                onSetVerbose = {
                    loggingVerbose = it
                    AppLogger.setVerbose(context, it)
                },
                onLogFileClick = onLogFileClick,
                onDeleteAll = { showDeleteAllConfirm = true },
            )
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.log_delete_confirm_text, Formatter.formatFileSize(context, totalSize)))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    AppLogger.clearLogs()
                    refresh()
                    showDeleteAllConfirm = false
                }) {
                    Text(stringResource(R.string.log_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LogsBody(
    context: Context,
    dailyLogs: List<AppLogger.LogFileMeta>,
    crashLogs: List<AppLogger.LogFileMeta>,
    totalSize: Long,
    loading: Boolean,
    loggingEnabled: Boolean,
    onToggleLogging: (Boolean) -> Unit,
    loggingVerbose: Boolean,
    onSetVerbose: (Boolean) -> Unit,
    onLogFileClick: (fileName: String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val anyFiles = dailyLogs.isNotEmpty() || crashLogs.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection(
            header = stringResource(R.string.log_section_logging),
            footer = stringResource(R.string.log_section_logging_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.log_enable_switch),
                checked = loggingEnabled,
                onCheckedChange = onToggleLogging,
                showDivider = false,
            )
        }

        if (loggingEnabled) {
            SettingsSection(
                header = stringResource(R.string.log_section_detail_level),
                footer = stringResource(R.string.log_detail_level_footer),
            ) {
                SettingsChoiceRow(
                    title = stringResource(R.string.log_detail_level_info),
                    selected = !loggingVerbose,
                    onSelect = { onSetVerbose(false) },
                )
                SettingsChoiceRow(
                    title = stringResource(R.string.log_detail_level_verbose),
                    selected = loggingVerbose,
                    onSelect = { onSetVerbose(true) },
                    showDivider = false,
                )
            }
        }

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        SettingsSection(
            header = stringResource(R.string.log_section_files),
            footer = stringResource(R.string.log_section_files_footer),
        ) {
            if (dailyLogs.isEmpty()) {
                SettingsValueRow(
                    title = stringResource(R.string.log_no_files),
                    value = "",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDivider = false,
                )
            } else {
                dailyLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < dailyLogs.size - 1,
                    )
                }
            }
        }

        if (crashLogs.isNotEmpty()) {
            SettingsSection(
                header = "Crash Logs",
                footer = "Most recent ${crashLogs.size} crash report(s) (Java/Kotlin + native).",
            ) {
                crashLogs.forEachIndexed { index, meta ->
                    SettingsValueRow(
                        title = meta.name,
                        value = Formatter.formatFileSize(context, meta.sizeBytes),
                        onClick = { onLogFileClick(meta.name) },
                        showDivider = index < crashLogs.size - 1,
                    )
                }
            }
        }

        if (anyFiles) {
            SettingsSection(
                header = stringResource(R.string.log_section_storage),
                footer = stringResource(R.string.log_section_storage_footer, Formatter.formatFileSize(context, totalSize)),
            ) {
                SettingsRow(
                    title = stringResource(R.string.log_delete_all_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteAll,
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}


private class LazyLogFile private constructor(
    private val raf: RandomAccessFile,
    val lineOffsets: LongArray,
) {
    val lineCount: Int get() = lineOffsets.size

    @Synchronized
    fun readLine(index: Int): String {
        if (index !in 0 until lineCount) return ""
        return try {
            raf.seek(lineOffsets[index])
            val buf = ByteArray(8 * 1024)
            val sb = StringBuilder()
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                var done = false
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        sb.append(String(buf, 0, i, Charsets.UTF_8))
                        done = true
                        break
                    }
                }
                if (done) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun close() = try { raf.close() } catch (_: Exception) {}

    companion object {
        fun open(file: File): LazyLogFile {
            val raf = RandomAccessFile(file, "r")
            val offsets = mutableListOf(0L)
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == '\n'.code.toByte()) {
                        offsets.add(pos + i + 1)
                    }
                }
                pos += n
            }
            val arr = if (offsets.size > 1 && offsets.last() >= raf.length()) {
                offsets.dropLast(1).toLongArray()
            } else {
                offsets.toLongArray()
            }
            raf.seek(0)
            return LazyLogFile(raf, arr)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var lazyLog by remember(fileName) { mutableStateOf<LazyLogFile?>(null) }
    var loadError by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        loading = true
        loadError = null
        lazyLog = withContext(Dispatchers.IO) {
            try {
                val file = File(File(context.filesDir, "logs"), fileName)
                if (!file.exists()) {
                    loadError = context.getString(R.string.log_not_found)
                    null
                } else {
                    LazyLogFile.open(file)
                }
            } catch (e: Exception) {
                loadError = context.getString(R.string.log_error_reading, e.message ?: "")
                null
            }
        }
        loading = false
    }

    DisposableEffect(fileName) {
        onDispose { lazyLog?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = File(File(context.filesDir, "logs"), fileName)
                        if (file.exists()) shareLogFile(context, file)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loadError ?: "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            lazyLog != null -> {
                val log = lazyLog!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    items(log.lineCount) { i ->
                        Text(
                            text = log.readLine(i),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}


private fun shareTraceExport(context: Context, otlp: Boolean = false) {
    val text = if (otlp) {
        com.openminis.app.observability.TraceLog.exportOtlpJson(sessionId = "minis")
    } else {
        com.openminis.app.observability.TraceLog.exportNdjson()
    }
    if (text.isBlank()) return
    val suffix = if (otlp) "otlp.json" else "ndjson"
    val file = File(context.cacheDir, "minis-trace-${System.currentTimeMillis()}.$suffix")
    runCatching { file.writeText(text) }.onSuccess { shareLogFile(context, file) }
}

private fun shareLogFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.log_share_chooser))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (e: Exception) {
        AppLogger.warning(
            "LogShare",
            "FileProvider share failed for ${file.name}: ${e.message} — falling back to EXTRA_TEXT",
        )
        val text = try { file.readText().take(100_000) } catch (_: Exception) { return }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_chooser)))
    }
}
