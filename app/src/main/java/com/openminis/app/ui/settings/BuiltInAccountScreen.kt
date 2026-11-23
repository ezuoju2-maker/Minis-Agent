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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.auth.BuiltInAccount
import com.openminis.app.auth.BuiltInAccountStore
import com.openminis.app.auth.BuiltInCheckin
import com.openminis.app.auth.BuiltInModel
import com.openminis.app.auth.BuiltInPlatformApi
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInAccountScreen(
    platform: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val spec = builtInPlatforms.firstOrNull { it.id == platform }
    val api = remember(platform) { BuiltInAccountStore.api(platform) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instanceId = "builtin-$platform"
    val providerType: ProviderType = when (platform) {
        "qoder" -> ProviderType.qoder
        "minimax" -> ProviderType.minimaxCode
        "workbuddy" -> ProviderType.workBuddy
        else -> spec?.providerType ?: ProviderType.unsupported
    }
    val regions = remember(platform) { BuiltInAccountStore.regions(platform) }

    var region by remember(platform) {
        mutableStateOf(BuiltInAccountStore.activeRegion(context, platform))
    }
    var account by remember(platform) {
        mutableStateOf(BuiltInAccountStore.load(context, platform, region))
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<BuiltInModel>>(emptyList()) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var quotaRows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loadedOnce by remember { mutableStateOf(false) }
    var loadedAt by remember { mutableStateOf<Long?>(null) }
    var userCode by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var checkin by remember { mutableStateOf<BuiltInCheckin?>(null) }
    var checkinBusy by remember { mutableStateOf(false) }

    val supportsCheckin = api?.supportsCheckin == true

    suspend fun loadCheckin() {
        val a = api ?: return
        val acc = account ?: return
        val r = a.fetchCheckin(acc)
        checkin = r
    }

    suspend fun doCheckin() {
        val a = api ?: return
        val acc = account ?: return
        checkinBusy = true
        val r = a.claimCheckin(acc)
        checkin = r.copy(message = null)
        checkinBusy = false
        r.message?.let { status = it }
    }

    suspend fun reload() {
        val acc = account ?: return
        val a = api ?: return
        busy = true
        error = null
        try {
            val result = a.fetchModels(acc)
            models = result.models
            modelsError = result.error
            quotaRows = runCatching { a.fetchQuota(acc) }.getOrDefault(emptyList())
            if (supportsCheckin) checkin = runCatching { a.fetchCheckin(acc) }.getOrNull()
            loadedAt = System.currentTimeMillis()
            loadedOnce = true
        } catch (e: Exception) {
            error = e.message ?: "同步失败"
        } finally {
            busy = false
        }
    }

    fun ensureProviderInstance(accessToken: String): Boolean {
        if (providerType == ProviderType.unsupported) return false
        val existing = providerRepository.instance(instanceId)
        val changed: Boolean
        if (existing == null) {
            providerRepository.addInstance(
                ProviderInstance(
                    id = instanceId,
                    label = spec?.name ?: platform,
                    providerType = providerType,
                    credentialType = ProviderCredential.oauth,
                )
            )
            changed = true
        } else if (existing.providerType != providerType || !existing.isEnabled) {
            providerRepository.updateInstance(
                existing.copy(providerType = providerType, isEnabled = true)
            )
            changed = true
        } else {
            changed = false
        }
        providerRepository.saveApiKey(instanceId, accessToken)
        return changed
    }

    LaunchedEffect(platform, region) {
        val acc0 = BuiltInAccountStore.load(context, platform, region)
        account = acc0
        models = emptyList()
        quotaRows = emptyList()
        modelsError = null
        loadedOnce = false
        loadedAt = null
        error = null
        status = null
        userCode = null
        if (acc0 != null) {
            val repaired = ensureProviderInstance(acc0.accessToken)
            reload()
            if (repaired) providerRepository.triggerAsyncModelReconcile(instanceId, forceRefresh = true)
        }
    }

    fun switchRegion(next: String) {
        if (next == region || busy) return
        BuiltInAccountStore.setActiveRegion(context, platform, next)
        region = next
    }

    fun startLogin() {
        val a = api ?: return
        if (busy) return
        busy = true
        error = null
        status = null
        userCode = null
        scope.launch {
            try {
                val start = a.beginLogin(region)
                userCode = start.userCode
                status = if (start.userCode != null) {
                    "已在浏览器中打开授权页，请输入下方验证码完成授权…"
                } else {
                    "已在浏览器中打开授权页，请在浏览器里完成登录…"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(start.authUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { error = "无法打开浏览器，请手动访问：${start.authUrl}" }

                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + 5 * 60 * 1000L
                var intervalMs = 3000L
                var acc: BuiltInAccount? = null
                var pollError: String? = null
                while (System.currentTimeMillis() < deadline) {
                    delay(intervalMs)
                    acc = try {
                        a.pollLogin(start.session)
                    } catch (e: Exception) {
                        pollError = e.message ?: "轮询异常"
                        null
                    }
                    if (acc != null) break
                    if (pollError != null) break
                    val waited = (System.currentTimeMillis() - startedAt) / 1000
                    status = "正在等待浏览器完成授权…已等待 ${waited}s" +
                        (a.pollNote?.let { "\n$it" } ?: "")
                    intervalMs = (intervalMs + 1000L).coerceAtMost(6000L)
                }
                if (pollError != null) {
                    error = "授权没有完成：$pollError" +
                        "\n可能原因：授权页已过期，或该站点（${regionLabel(regions, region)}）与账号不匹配。" +
                        "\n建议：重新点一次登录；若反复失败，切到另一个地区再试。"
                    status = null
                    return@launch
                }
                if (acc == null) {
                    error = "等了 5 分钟仍未检测到授权完成（当前站点：${regionLabel(regions, region)}）。" +
                        "\n可能原因：浏览器里的授权没有点「确认」、登录了错误的账号、授权页已过期，或者你登录的是另一个地区的账号。" +
                        "\n建议：重新点一次登录，并留意浏览器是否停在授权确认页；若反复失败，切到另一个地区再试。"
                    status = null
                    return@launch
                }
                BuiltInAccountStore.save(context, acc)
                account = acc
                ensureProviderInstance(acc.accessToken)
                providerRepository.triggerAsyncModelReconcile(instanceId, forceRefresh = true)
                status = "登录成功，正在拉取模型目录…"
                userCode = null
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

    fun signOut() {
        BuiltInAccountStore.clear(context, platform, region)
        runCatching { providerRepository.removeInstance(instanceId) }
        account = null
        models = emptyList()
        quotaRows = emptyList()
        modelsError = null
        loadedOnce = false
        loadedAt = null
        status = null
        error = null
        userCode = null
    }

    fun copyUserCode(code: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("code", code))
        status = "验证码已复制"
    }

    fun copyUid() {
        val uid = account?.uid?.takeIf { it.isNotBlank() } ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("uid", uid))
        status = "账号 uid 已复制"
    }

    val filtered = remember(models, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter {
            it.id.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                (it.vendor ?: "").lowercase().contains(q)
        }
    }

    fun q(label: String): Double? = quotaRows.firstOrNull { it.first == label }?.second
        ?.replace(",", "")?.toDoubleOrNull()
    val qRemain = q("剩余额度") ?: q("remaining") ?: q("remain")
    val qTotal = q("总额度") ?: q("total")
    val qUsedPct = q("已用占比")
    val qRemainPct: Double? = when {
        qUsedPct != null -> (100 - qUsedPct).coerceIn(0.0, 100.0)
        qRemain != null && qTotal != null && qTotal > 0 -> (qRemain / qTotal * 100).coerceIn(0.0, 100.0)
        else -> null
    }
    val progress: Float? = qRemainPct?.let { (it / 100.0).toFloat() }

    SettingsScaffold(title = spec?.name ?: platform, onBack = onBack) {
        if (regions.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                regions.forEach { r ->
                    val selected = r.id == region
                    val signedIn = remember(region, r.id) {
                        BuiltInAccountStore.load(context, platform, r.id) != null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
                            )
                            .clickable(enabled = !busy) { switchRegion(r.id) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (signedIn) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF34C759), CircleShape),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "两个站点账号与令牌互不通用：国内版用国内手机号/邮箱登录，国际版用全球账号登录。" +
                    "切换后各自独立保存，互不覆盖。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                spec?.let {
                    Image(
                        painter = painterResource(it.logoRes),
                        contentDescription = spec.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account?.nickname?.takeIf { it.isNotBlank() } ?: (spec?.name ?: platform),
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
                                    if (account != null) Color(0xFF34C759) else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (account != null) {
                                "已登录 · " + regionLabel(regions, region) +
                                    (account?.uid?.takeIf { it.isNotBlank() }?.let {
                                        " · " + if (it.length > 10) it.take(10) + "…" else it
                                    } ?: "")
                            } else "未登录 · " + regionLabel(regions, region),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            if (account != null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTileSmall("可用模型", if (loadedOnce) "${models.size}" else "…", Modifier.weight(1f))
                    StatTileSmall(
                        "剩余额度",
                        qRemain?.let { fmt(it) }
                            ?: qRemainPct?.let { "${it.toInt()}%" }
                            ?: if (loadedOnce) "—" else "…",
                        Modifier.weight(1f),
                    )
                    StatTileSmall(
                        "总量",
                        qTotal?.let { fmt(it) }
                            ?: (quotaRows.firstOrNull { it.first == "套餐" }?.second?.takeIf { it.length <= 10 })
                            ?: if (loadedOnce) "—" else "…",
                        Modifier.weight(1f),
                    )
                }

                if (progress != null || (qRemain != null && qTotal == null)) {
                    val p = progress ?: 1f
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF0EC8A9),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            qRemain != null && qTotal != null ->
                                "已用 ${fmt(qTotal - qRemain)} · 剩余 ${fmt(qRemain)} / ${fmt(qTotal)}（${(p * 100).toInt()}%）"
                            qRemain != null && qTotal == null ->
                                "积分余额 ${fmt(qRemain)} · 按量计费，无总额上限"
                            else -> "剩余 ${(p * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                if (account?.uid?.isNotBlank() == true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { copyUid() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
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
                }
                Text(
                    text = "站点 ${quotaRows.firstOrNull { it.first == "站点" }?.second ?: regionLabel(regions, region)}" +
                        " · ${regionLabel(regions, region)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                loadedAt?.let {
                    Text(
                        text = "最后同步 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        userCode?.let { code ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { copyUserCode(code) }
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "浏览器中若要求输入验证码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "点此复制",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

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
                                cm?.setPrimaryClip(ClipData.newPlainText("error", e))
                                status = "错误详情已复制"
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        if (account == null) {
            Spacer(Modifier.height(8.dp))
            MinisButton(
                onClick = { startLogin() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    if (regions.size > 1) "登录 ${spec?.name ?: platform}（${regionLabel(regions, region)}）"
                    else "登录 ${spec?.name ?: platform}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "点击后会用系统浏览器打开官方授权页，登录完成后返回本应用即可，无需填写 API Key 或接口地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            return@SettingsScaffold
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MinisButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("刷新数据")
            }
            if (supportsCheckin) {
                MinisButton(
                    onClick = { scope.launch { runCatching { doCheckin() }.onFailure { error = it.message } } },
                    enabled = !busy && !checkinBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (checkinBusy) "签到中…" else "每日签到")
                }
            } else {
                MinisButton(onClick = { startLogin() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("重新登录")
                }
            }
        }

        if (supportsCheckin && checkin != null) {
            val ci = checkin!!
            SettingsSection(header = "每日签到") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ci.claimedToday) {
                            Text("今日已签到", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, color = Color(0xFF0EC8A9))
                        } else if (ci.canClaim) {
                            Text("今日可领取", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, color = Color(0xFFF59E0B))
                        } else {
                            Text("未签到", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (ci.days.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ci.days.forEach { d ->
                                val color = when (d.state) {
                                    "claimed" -> Color(0xFF0EC8A9)
                                    "claimable" -> Color(0xFFF59E0B)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = if (d.state == "upcoming") 0.25f else 0.18f))
                                        .padding(vertical = 8.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "第${d.dayNo}天",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    Text(
                                        compactPoints(d.points + d.bonusPoints),
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
                    val note = ci.message?.takeIf { m ->
                        !m.startsWith("今日已签到") &&
                            !m.startsWith("今日有可领取") &&
                            !m.startsWith("签到成功") &&
                            !m.startsWith("签到未完成")
                    }
                    note?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ci.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

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

        Text(
            text = "以上数据来自 ${spec?.name ?: platform} 官方接口实时拉取（模型目录、套餐与额度），不是本地预置列表。" +
                "该平台为第三方非官方对接，接口可能随官方客户端更新而变化。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MinisButton(onClick = { startLogin() }, enabled = !busy, modifier = Modifier.weight(1f)) {
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

@Composable
private fun ModelRow(m: BuiltInModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                m.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            m.priceFactor?.let {
                Text(
                    "×${trimNum(it)}",
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
                buildString {
                    append(m.id)
                    m.vendor?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
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
            if (m.recommended) BadgeSmall("推荐", Color(0xFF0EC8A9))
            if (m.free) BadgeSmall("免费", Color(0xFF34C759))
            if (m.vision) BadgeSmall("视觉", Color(0xFF3B82F6))
            if (m.reasoning) BadgeSmall(if (m.onlyReasoning) "仅推理" else "推理", Color(0xFF8B5CF6))
            if (m.tools) BadgeSmall("工具", Color(0xFFF59E0B))
        }
    }
}

@Composable
private fun StatTileSmall(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BadgeSmall(text: String, color: Color) {
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

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format("%.2f", v).trimEnd('0').trimEnd('.')

private fun trimNum(v: Double): String = fmt(v)

private fun compactPoints(v: Int): String = when {
    v <= 0 -> "+0"
    v >= 1000 && v % 1000 == 0 -> "+${v / 1000}k"
    v >= 1000 -> "+${String.format("%.1f", v / 1000f)}k"
    else -> "+$v"
}

private fun regionLabel(regions: List<com.openminis.app.auth.BuiltInRegion>, id: String): String =
    regions.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() } ?: "默认站点"
