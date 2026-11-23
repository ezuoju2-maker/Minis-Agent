package com.openminis.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.auth.WorkBuddyApi
import com.openminis.app.auth.WorkBuddyOAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-workbuddy] WorkBuddy platform screen — reached from Built-in Models.
 *
 * Sign-in opens the SYSTEM browser (the user asked for this explicitly); the
 * app keeps polling the token endpoint in the background and picks the account
 * up when the user returns. Everything else (models, quota, check-in, rewards)
 * is done in-app with no manual configuration.
 *
 * Every number on this screen comes from an upstream endpoint:
 *  - model count / rows  -> /console/enterprises/personal/models
 *  - remaining / total   -> /billing/meter/get-user-resource-*
 *  - check-in delta      -> balance snapshot diff around /daily-checkin
 *  - streak              -> /activity/growth/streak  (hidden when unavailable)
 *  - gift / compensation -> /billing/meter/claim-gift, /claim-compensation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkBuddyPlatformScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instanceId = "workbuddy-builtin"

    var account by remember {
        mutableStateOf(WorkBuddyOAuthManager.loadAccount(context, instanceId))
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<WorkBuddyApi.WorkBuddyModel>>(emptyList()) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf<WorkBuddyApi.Credits?>(null) }
    var streak by remember { mutableStateOf<WorkBuddyApi.Streak?>(null) }
    var heatcells by remember { mutableStateOf<List<WorkBuddyApi.DayCell>>(emptyList()) }
    var loadedOnce by remember { mutableStateOf(false) }
    var loadedAt by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    // Site the next sign-in targets; once signed in it mirrors the account.
    var selectedRegion by remember {
        mutableStateOf(
            if (account?.region == "international") "international" else "domestic"
        )
    }

    suspend fun reload() {
        val acc = account ?: return
        busy = true
        error = null
        try {
            val m = WorkBuddyApi.fetchModelsDetailed(acc)
            models = m.models
            modelsError = m.error
            credits = runCatching { WorkBuddyApi.fetchCredits(acc) }
                .getOrElse { WorkBuddyApi.Credits(null, null, error = it.message ?: "额度获取失败") }
            streak = runCatching { WorkBuddyApi.fetchStreak(acc) }.getOrNull()
            heatcells = runCatching { WorkBuddyApi.fetchHeatmap(acc) }.getOrNull().orEmpty()
            loadedAt = System.currentTimeMillis()
            loadedOnce = true
        } catch (e: Exception) {
            error = e.message ?: "同步失败"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (account != null) reload()
    }

    fun signIn() {
        if (busy) return
        busy = true
        error = null
        status = "已打开浏览器，请在浏览器中完成登录…"
        scope.launch {
            try {
                var inst = providerRepository.instance(instanceId)
                if (inst == null) {
                    inst = ProviderInstance(
                        id = instanceId,
                        label = "WorkBuddy",
                        providerType = ProviderType.workBuddy,
                        credentialType = ProviderCredential.oauth,
                    )
                    providerRepository.addInstance(inst)
                }
                val manager = WorkBuddyOAuthManager(context, instanceId)
                val acc = manager.login(
                    repo = providerRepository,
                    region = selectedRegion,
                    onAuthUrl = { session ->
                        // Open the system browser (not an embedded webview).
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.authUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                )
                account = acc
                status = "登录成功，正在同步…"
                providerRepository.triggerAsyncModelReconcile(instanceId, forceRefresh = true)
                reload()
                status = null
            } catch (e: Exception) {
                error = e.message ?: "登录失败"
                status = null
            } finally {
                busy = false
            }
        }
    }

    fun refresh() {
        if (busy) return
        scope.launch { runCatching { reload() }.onFailure { error = it.message } }
    }

    fun checkin() {
        val acc = account ?: return
        if (busy) return
        busy = true
        status = null
        error = null
        scope.launch {
            try {
                val result = WorkBuddyApi.checkin(acc, credits?.remaining)
                result.credits?.let { credits = it }
                status = when {
                    result.gained != null && result.gained > 0 -> "签到成功，+${fmt(result.gained)} 额度"
                    result.alreadyChecked -> "今日已签到"
                    else -> result.message
                }
                streak = runCatching { WorkBuddyApi.fetchStreak(acc) }.getOrNull()
                // The provider list must see the new balance too.
                providerRepository.triggerAsyncModelReconcile(instanceId, forceRefresh = false)
            } catch (e: Exception) {
                error = e.message ?: "签到失败"
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        WorkBuddyOAuthManager(context, instanceId).logout()
        runCatching { providerRepository.removeInstance(instanceId) }
        account = null
        models = emptyList()
        credits = null
        streak = null
        modelsError = null
        loadedOnce = false
        loadedAt = null
        status = null
        error = null
    }

    fun copyUid() {
        val uid = account?.uid ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("WorkBuddy uid", uid))
        status = "uid 已复制"
    }

    val filtered = remember(models, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter {
            it.id.lowercase().contains(q) ||
                it.displayName.lowercase().contains(q) ||
                (it.vendor ?: "").lowercase().contains(q)
        }
    }

    SettingsScaffold(title = "WorkBuddy", onBack = onBack) {
        // ── Account card ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_workbuddy),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account?.nickname?.takeIf { it.isNotBlank() } ?: "WorkBuddy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (account != null) Color(0xFF34C759)
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (account != null) {
                                "已登录 · ${account?.uid?.takeIf { it.isNotBlank() }?.let { if (it.length > 10) it.take(10) + "…" else it } ?: "uid 未知"}"
                            } else "未登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            if (account != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("可用模型", if (loadedOnce) "${models.size}" else "…", Modifier.weight(1f))
                    StatTile(
                        "剩余额度",
                        credits?.remaining?.let { fmt(it) } ?: if (loadedOnce) "—" else "…",
                        Modifier.weight(1f),
                    )
                    StatTile(
                        "总量",
                        credits?.total?.let { fmt(it) } ?: if (loadedOnce) "—" else "…",
                        Modifier.weight(1f),
                    )
                }

                // Quota bar — only when the upstream gave both numbers.
                val remain = credits?.remaining
                val total = credits?.total
                if (remain != null && total != null && total > 0) {
                    Spacer(Modifier.height(12.dp))
                    val ratio = (remain / total).coerceIn(0.0, 1.0).toFloat()
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF0EC8A9),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已用 ${fmt(total - remain)} · 剩余 ${fmt(remain)} / ${fmt(total)}（${(ratio * 100).toInt()}%）",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Detail rows: uid copy / domain / expiry / last sync.
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { copyUid() }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "账号 uid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(74.dp),
                    )
                    Text(
                        account?.uid.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "复制",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "服务域名 ${account?.domain.orEmpty()} · ${if (account?.region == "international") "国际版" else "国内版"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                loadedAt?.let {
                    Text(
                        text = "最后同步 ${timeOf(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Messages ────────────────────────────────────────────────────
        // Errors are shown as a full sentence-level explanation (what failed,
        // which endpoint, what upstream said, what to try), not a bare code —
        // a "HTTP 400" alone tells the user nothing actionable.
        error?.let { e ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "操作没有完成",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "复制详情",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("WorkBuddy error", e))
                                status = "错误详情已复制"
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = e,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        status?.let { s ->
            Text(
                text = s,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (account == null) {
            Spacer(Modifier.height(12.dp))
            // WorkBuddy runs two separate sites (workbuddy.cn / codebuddy.ai).
            // Accounts and quotas do not carry over, so the site is picked here.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("domestic" to "国内版", "international" to "国际版").forEach { (id, label) ->
                    val selected = id == selectedRegion
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable(enabled = !busy) { selectedRegion = id }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = if (selectedRegion == "international") {
                    "国际版：用 codebuddy.ai 账号登录，模型与额度与国际站一致。"
                } else {
                    "国内版：用 workbuddy.cn / codebuddy.cn 账号登录，国内网络更稳定。"
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            MinisButton(
                onClick = { signIn() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    "登录 WorkBuddy（${if (selectedRegion == "international") "国际版" else "国内版"}）",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "点击后会在系统浏览器中打开登录页，登录完成后返回本应用即可，无需填写 API Key 或接口地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            return@SettingsScaffold
        }

        // ── Actions ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MinisButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("刷新数据")
            }
            MinisButton(onClick = { checkin() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("每日签到")
            }
        }

        credits?.expireAt?.let {
            Text(
                "额度到期：${fmtDate(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        credits?.error?.let {
            Text(
                "额度获取失败：$it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // ── Check-in panel: one-week grid, same style as MiniMax / Qoder ──
        // Replaced the old 28-day heatmap with a compact 7-day row so every
        // platform uses the identical check-in card. Data is still the real
        // /activity/growth/heatmap result (last 7 days), never fabricated.
        if (streak != null || heatcells.isNotEmpty()) {
            SettingsSection(header = "每日签到") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(14.dp),
                ) {
                    // Status chip — identical to Qoder / MiniMax so all three
                    // check-in cards share the same structure. "Today" is the
                    // latest heatmap day whose date equals the local calendar
                    // day with a positive score; never fabricate a check-in.
                    val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val signedToday = heatcells.lastOrNull()?.let { it.date == todayStr && it.score > 0 } == true
                    Text(
                        if (signedToday) "今日已签到" else "未签到",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (signedToday) Color(0xFF0EC8A9)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val week = heatcells.takeLast(7)
                    if (week.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            week.forEachIndexed { idx, cell ->
                                val on = cell.score > 0
                                val color = if (on) Color(0xFF0EC8A9)
                                else MaterialTheme.colorScheme.outlineVariant
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = if (on) 0.18f else 0.12f))
                                        .padding(vertical = 8.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "第${idx + 1}天",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    Text(
                                        if (on) "+${cell.score}" else "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = color,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Models ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "可用模型（${filtered.size}${if (query.isNotBlank()) " / ${models.size}" else ""}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "上游实时目录",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (models.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索模型名称 / ID / 厂商", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
            SettingsSection {
                filtered.forEachIndexed { i, m ->
                    ModelRow(m)
                    if (i < filtered.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        } else if (loadedOnce) {
            Text(
                text = "模型获取失败：${modelsError ?: "未知原因"}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // ── Footer / account actions ───────────────────────────────────
        Text(
            text = "以上数据全部来自 WorkBuddy 服务端接口（模型目录、计费资源包、签到接口），非本地预置。签到接口本身不返回积分数，此处显示的是签到前后余额快照的差值。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MinisButton(onClick = { signIn() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("重新登录")
            }
            MinisButton(
                onClick = { signOut() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("退出登录") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One catalogue row: everything shown is a field the upstream actually sent. */
@Composable
private fun ModelRow(m: WorkBuddyApi.WorkBuddyModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = m.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            m.creditsPerCall?.let { c ->
                Text(
                    text = "×${trimNum(c)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            m.contextWindow?.let {
                Text(
                    "${it / 1000}K",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(m.id)
                    m.vendor?.takeIf { it.isNotBlank() && !it.equals(m.displayName, true) }?.let { append(" · $it") }
                    m.maxOutputTokens?.let { append(" · 输出 ${it / 1000}K") }
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            if (m.recommended) Badge("推荐", Color(0xFF0EC8A9))
            if (m.supportsVision) Badge("视觉", Color(0xFF3B82F6))
            if (m.supportsReasoning) Badge(if (m.onlyReasoning) "仅推理" else "推理", Color(0xFF8B5CF6))
            if (m.supportsTools) Badge("工具", Color(0xFFF59E0B))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 9.sp,
        color = color,
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.')

private fun fmt(v: Double?): String {
    if (v == null) return "—"
    return if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
}

private fun fmtDate(epochSec: Long): String {
    return try {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        f.format(Date(epochSec * 1000))
    } catch (_: Exception) { "—" }
}

private fun timeOf(millis: Long): String =
    try { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis)) } catch (_: Exception) { "—" }
