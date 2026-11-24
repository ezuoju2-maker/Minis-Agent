package com.openminis.app.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

object MiniMaxCodeApi : BuiltInPlatformApi {
    override val displayName: String = "MiniMax Code"

    override val regions: List<BuiltInRegion> = listOf(BuiltInRegion.CN, BuiltInRegion.INTL)

    private const val TAG = "MiniMaxCodeApi"
    private const val CLIENT_ID = "mcode-public"
    private const val SCOPE = "agent.default"
    private const val AUDIENCE = "agent-backend"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private const val UA = "mcode/0.6.9"

    private fun accountBase(region: String) =
        if (region == BuiltInRegion.INTL.id) "https://account.minimax.io" else "https://account.minimax.cn"

    private fun agentBase(region: String) =
        if (region == BuiltInRegion.INTL.id) "https://agent.minimax.io" else "https://agent.minimaxi.com"

    private fun webBase(region: String) =
        if (region == BuiltInRegion.INTL.id) "https://platform.minimax.io" else "https://www.minimaxi.com"

    private const val BUILD_ENV = "prod"

    private fun llmBase(region: String) = agentBase(region) + "/mavis/api/v1/llm/v1"

    fun messagesBase(region: String): String =
        if (region == BuiltInRegion.INTL.id) {
            "https://agent.minimax.io/mavis/api/v1/llm"
        } else {
            "https://agent.minimax.cn/mavis/api/v1/llm"
        }

    fun chatEndpoint(region: String) = llmBase(region) + "/chat/completions"

    val DEFAULT_CHAT_BASE: String = "https://agent.minimaxi.com/mavis/api/v1/llm/v1"

    @Deprecated("Region specific; use chatEndpoint(region)")
    val CHAT_ENDPOINT: String get() = chatEndpoint(BuiltInRegion.DEFAULT)

    fun siteOf(account: BuiltInAccount): String =
        if (account.region == BuiltInRegion.INTL.id) "minimax.io" else "minimax.cn"

    override val supportsCheckin: Boolean = true

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun verifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return b64Url(bytes)
    }

    private fun challenge(v: String): String =
        b64Url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))

    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, String> {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(body)
            .build()
        client.newCall(req).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get()
            .build()
        client.newCall(req).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    private fun getPublic(url: String): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get()
            .build()
        client.newCall(req).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    override suspend fun beginLogin(region: String): BuiltInLoginStart = withContext(Dispatchers.IO) {
        val site = if (region == BuiltInRegion.INTL.id) BuiltInRegion.INTL.id else BuiltInRegion.CN.id
        val base = accountBase(site)
        val v = verifier()
        val (code, text) = postForm(
            "$base/oauth2/device/code",
            mapOf(
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
                "audience" to AUDIENCE,
                "code_challenge" to challenge(v),
                "code_challenge_method" to "S256",
            ),
        )
        if (code !in 200..299) {
            throw IllegalStateException(
                "MiniMax 设备码申请失败（站点 $base）HTTP $code" + snippet(text) +
                    "\n可能原因：该站点当前不可达，或者你需要的是另一个地区的版本。" +
                    "\n建议：切到另一个地区再试一次。",
            )
        }
        val root = JSONObject(text)
        val deviceCode = root.optString("device_code", "")
        val userCode = root.optString("user_code", "")
        val url = root.optString("verification_uri_complete", "")
            .ifEmpty { root.optString("verification_uri", "") }
            .ifEmpty { "$base/oauth-authorize?user_code=" + URLEncoder.encode(userCode, "UTF-8") }
        if (deviceCode.isBlank() || url.isBlank()) {
            throw IllegalStateException("MiniMax 设备码响应缺少 device_code/verification_uri" + snippet(text))
        }
        BuiltInLoginStart(
            authUrl = url,
            userCode = userCode.ifBlank { null },
            session = BuiltInAccount(
                platform = "minimax",
                accessToken = "",
                refreshToken = null,
                uid = "",
                region = site,
                extra = mapOf(
                    "deviceCode" to deviceCode,
                    "verifier" to v,
                    "interval" to root.optInt("interval", 5).toString(),
                ),
            ),
        )
    }

    override suspend fun pollLogin(state: BuiltInAccount): BuiltInAccount? = withContext(Dispatchers.IO) {
        val deviceCode = state.extra["deviceCode"].orEmpty()
        val v = state.extra["verifier"].orEmpty()
        if (deviceCode.isBlank()) return@withContext null
        val (code, text) = try {
            postForm(
                "${accountBase(state.region)}/oauth2/token",
                mapOf(
                    "grant_type" to DEVICE_GRANT,
                    "device_code" to deviceCode,
                    "client_id" to CLIENT_ID,
                    "code_verifier" to v,
                    "scope" to SCOPE,
                    "audience" to AUDIENCE,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "poll error: ${e.message}")
            return@withContext null
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val err = root.optString("error", "")
        if (err == "authorization_pending" || err == "slow_down") return@withContext null
        if (err.isNotBlank()) throw IllegalStateException("MiniMax 授权失败：$err" + snippet(text))
        val token = root.optString("access_token", "")
        if (token.isBlank()) return@withContext null
        BuiltInAccount(
            platform = "minimax",
            accessToken = token,
            refreshToken = root.optString("refresh_token", "").ifEmpty { null },
            uid = root.optString("sub", "").ifEmpty { root.optString("account_id", "") },
            expiresAt = System.currentTimeMillis() + root.optLong("expires_in", 0) * 1000,
            region = state.region,
        )
    }

    override suspend fun refresh(account: BuiltInAccount): BuiltInAccount? = withContext(Dispatchers.IO) {
        val rt = account.refreshToken ?: return@withContext null
        val (code, text) = try {
            postForm(
                "${accountBase(account.region)}/oauth2/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to rt,
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE,
                    "audience" to AUDIENCE,
                ),
            )
        } catch (_: Exception) {
            return@withContext null
        }
        if (code !in 200..299) return@withContext null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val token = root.optString("access_token", "")
        if (token.isBlank()) return@withContext null
        account.copy(
            accessToken = token,
            refreshToken = root.optString("refresh_token", "").ifEmpty { account.refreshToken },
            expiresAt = System.currentTimeMillis() + root.optLong("expires_in", 0) * 1000,
        )
    }

    override suspend fun fetchModels(account: BuiltInAccount): BuiltInModels = withContext(Dispatchers.IO) {
        val agent = agentBase(account.region)
        val regionTag = if (account.region == BuiltInRegion.INTL.id) "en" else "cn"
        val catalogUrl = "$agent/mavis/api/v1/models?region=$regionTag&buildEnv=$BUILD_ENV"
        val (code, text) = try {
            getPublic(catalogUrl)
        } catch (e: Exception) {
            return@withContext BuiltInModels(emptyList(), "模型目录请求失败：${e.message}")
        }
        if (code !in 200..299) {
            return@withContext BuiltInModels(
                emptyList(),
                "模型目录返回 HTTP $code" + snippet(text) +
                    "\n建议：过几分钟点「刷新数据」重试；这是上游服务状态，与账号无关。",
            )
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext BuiltInModels(emptyList(), "模型目录响应不是 JSON")
        val out = mutableListOf<BuiltInModel>()
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val cfg = providers.optJSONObject(i)?.optJSONObject("config") ?: continue
                val models = cfg.optJSONObject("models") ?: continue
                val whitelist = mutableSetOf<String>()
                cfg.optJSONArray("whitelist")?.let { arr ->
                    for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let { whitelist += it }
                }
                val keys = models.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    if (whitelist.isNotEmpty() && id !in whitelist) continue
                    val m = models.optJSONObject(id) ?: continue
                    val limit = m.optJSONObject("limit")
                    val inputs = m.optJSONObject("modalities")?.optJSONArray("input")
                    val vision = inputs?.let { arr ->
                        (0 until arr.length()).any { arr.optString(it) in listOf("image", "video") }
                    } ?: false
                    out += BuiltInModel(
                        id = id,
                        name = m.optString("name", "").ifEmpty { id },
                        vendor = cfg.optString("name", "").ifEmpty { null },
                        contextWindow = limit?.optInt("context", 0)?.takeIf { it > 0 },
                        maxOutputTokens = limit?.optInt("output", 0)?.takeIf { it > 0 },
                        vision = vision || m.optBoolean("attachment", false),
                        reasoning = m.optBoolean("reasoning", false),
                    )
                }
            }
        }
        if (out.isEmpty()) {
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models")
            arr?.let {
                for (i in 0 until it.length()) {
                    val m = it.optJSONObject(i) ?: continue
                    val id = m.optString("id", "").ifEmpty { m.optString("model", "") }
                    if (id.isBlank()) continue
                    out += BuiltInModel(
                        id = id,
                        name = m.optString("display_name", "").ifEmpty { m.optString("name", id) },
                    )
                }
            }
        }
        if (out.isEmpty()) {
            BuiltInModels(emptyList(), "模型目录里没有可用模型" + snippet(text))
        } else {
            BuiltInModels(out.distinctBy { it.id }, null)
        }
    }

    override suspend fun fetchQuota(account: BuiltInAccount): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "站点" to siteOf(account)

        fun postMatrix(path: String, bodyObj: String): JSONObject? {
            val req = signedDailyRequest(account, path, "POST", bodyObj, devicePlatform = "mcode")
            val (code, text) = try {
                request(req, "POST", bodyObj)
            } catch (_: Exception) {
                return null
            }
            if (code !in 200..299) return null
            return runCatching { JSONObject(text) }.getOrNull()
        }

        fun firstObj(root: JSONObject?, key: String): JSONObject? {
            if (root == null) return null
            val direct = root.optJSONObject(key)
            if (direct != null) return direct
            return root.optJSONObject("data")?.optJSONObject(key)
        }

        var credit: String? = null
        var opGroupId: String? = null
        var tier: String? = null
        var planFlag: Boolean? = null

        val extra = postMatrix("/matrix/api/v1/user/get_user_extra_info", "{}")
        if (extra != null) {
            val workspaces = extra.optJSONArray("workspaces")
                ?: extra.optJSONObject("data")?.optJSONArray("workspaces")
            if (workspaces != null) {
                for (i in 0 until workspaces.length()) {
                    val w = workspaces.optJSONObject(i) ?: continue
                    if ((w.opt("workspace_type") as? Number)?.toInt() ?: -1 != 0) continue
                    val sum = w.optJSONObject("op_credit_summary")
                    credit = credit
                        ?: sum?.optString("total_remaining_amount", "").orEmpty().ifBlank { null }
                        ?: w.optString("total_remaining_amount", "").ifBlank { null }
                    opGroupId = opGroupId ?: w.optString("op_group_id", "").ifBlank { null }
                    break
                }
            }
        }

        val member = postMatrix("/matrix/api/v1/commerce/get_membership_info", "{}")
        if (member != null) {
            val m = member.optJSONObject("data") ?: member
            if (m.has("has_token_plan")) planFlag = m.optBoolean("has_token_plan")
            tier = tier ?: m.optString("token_plan_tier", "").ifBlank { null }
            val sum = m.optJSONObject("op_credit_summary")
            credit = credit
                ?: sum?.optString("total_remaining_amount", "").orEmpty().ifBlank { null }
                ?: m.optString("opcredit_balance", "").ifBlank { null }
            opGroupId = opGroupId ?: m.optString("op_group_id", "").ifBlank { null }
        }

        val creditNum = credit?.replace(",", "")?.toDoubleOrNull()
        if (creditNum != null) {
            rows += "剩余额度" to trimNum(creditNum)
        } else if (planFlag == false) {
            rows += "剩余额度" to "未开通"
        }

        if (!opGroupId.isNullOrBlank()) {
            val url = "${webBase(account.region)}/v1/api/openplatform/coding_plan/remains" +
                "?business=Agent&device_platform=mcode"
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${account.accessToken}")
                .header("Accept", "application/json")
                .header("X-Group-Id", opGroupId!!)
                .header("User-Agent", UA)
                .get().build()
            val result = runCatching {
                client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            }.getOrNull()
            if (result != null) {
                val (_, text) = result
                val root = runCatching { JSONObject(text) }.getOrNull()
                val sc = root?.optJSONObject("base_resp")?.optInt("status_code", -1) ?: -1
                if (sc == 0 && root != null) {
                    val data = root.optJSONObject("data") ?: root
                    val arr = data.optJSONArray("model_remains") ?: root.optJSONArray("model_remains")
                    val m = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
                        .firstOrNull { it.optString("model_name").equals("general", true) }
                        ?: (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
                            .firstOrNull { it.has("current_interval_total_count") }
                    if (m != null) {
                        val total = (m.opt("current_interval_total_count") as? Number)?.toDouble()
                        val used = (m.opt("current_interval_usage_count") as? Number)?.toDouble()
                        if (total != null && total > 0 && used != null) {
                            if (rows.none { it.first == "剩余额度" }) rows += "剩余额度" to trimNum(used)
                            rows += "总额度" to trimNum(total)
                        }
                    }
                }
            }
        }

        if (rows.none { it.first == "总额度" }) {
            rows += "套餐" to (tier ?: "按量计费")
        } else {
            tier?.let { rows += "套餐" to it }
        }
        rows
    }

    private fun quotaLabel(key: String): String = when (key) {
        "total_count" -> "总额度"
        "remain_count", "remaining_count" -> "剩余额度"
        "used_count" -> "已用额度"
        "start_time" -> "周期开始"
        "end_time", "expire_time" -> "周期结束"
        else -> key
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.')

    private fun snippet(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isBlank()) return ""
        val msg = runCatching {
            val o = JSONObject(t)
            o.optString("error_description", o.optString("message", o.optString("error", "")))
        }.getOrDefault("")
        return if (msg.isNotBlank()) "，上游说明「${msg.take(100)}」" else "，响应「${t.take(100)}」"
    }

    private val SIGN_SECRET = "I*7Cf%WZ#S&%1RlZJ&C2"

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class SignedReq(val url: String, val headers: Map<String, String>)

    private fun signedDailyRequest(
        account: BuiltInAccount,
        path: String,
        method: String,
        body: String?,
        extraQuery: Map<String, String> = emptyMap(),
        devicePlatform: String = "web",
    ): SignedReq {
        val regionTag = if (account.region == BuiltInRegion.INTL.id) "en" else "cn"
        val lang = if (regionTag == "cn") "zh" else "en"
        val nowMs = System.currentTimeMillis()
        val urlBase = agentBase(account.region) + path
        val query = LinkedHashMap<String, String>().apply {
            putAll(extraQuery)
            put("device_platform", devicePlatform)
            put("biz_id", "3")
            put("app_id", "3001")
            put("version_code", "22201")
            put("is_desktop", "1")
            put("desktop_version", "0.6.9")
            put("unix", nowMs.toString())
            put("timezone_offset", (java.util.TimeZone.getDefault().rawOffset / -1000).toString())
            put("sys_language", lang)
            put("lang", lang)
            put("device_id", "0")
            put("os_name", "android")
            put("browser_name", "mcode")
            put("user_id", account.uid)
            put("client", "mcode")
        }
        val queryStr = query.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val pathAndQuery = "$path?$queryStr"
        val b = body ?: "{}"
        val ts = (nowMs / 1000).toString()
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "User-Agent" to "MiniMaxCode",
            "Authorization" to "Bearer ${account.accessToken}",
            "yy" to md5(URLEncoder.encode(pathAndQuery, "UTF-8") + "_" + b + md5(nowMs.toString()) + "ooui"),
            "x-timestamp" to ts,
            "x-signature" to md5(ts + SIGN_SECRET + b),
        )
        return SignedReq(urlBase + "?" + queryStr, headers)
    }

    private fun request(req: SignedReq, method: String, body: String?): Pair<Int, String> {
        val builder = Request.Builder().url(req.url)
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        if (method == "POST") {
            builder.post(okhttp3.RequestBody.create(null, body ?: "{}"))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { return it.code to it.body?.string().orEmpty() }
    }

    private fun parsePanel(root: JSONObject): BuiltInCheckin {
        val data = root.optJSONObject("data") ?: root
        val daysArr = data.optJSONArray("days")
        val days = mutableListOf<CheckinDay>()
        if (daysArr != null) {
            for (i in 0 until daysArr.length()) {
                val d = daysArr.optJSONObject(i) ?: continue
                val stateNum = d.optInt("status", 0)
                val state = when (stateNum) {
                    2 -> "claimable"
                    3 -> "claimed"
                    1 -> "upcoming"
                    4 -> "disabled"
                    else -> "upcoming"
                }
                days += CheckinDay(
                    dayNo = d.optInt("day_no", i + 1),
                    points = d.optInt("points", 0),
                    bonusPoints = d.optInt("bonus_points", 0),
                    isToday = d.optBoolean("is_today", false),
                    state = state,
                )
            }
        }
        val sorted = days.sortedBy { it.dayNo }
        val todayIdx = sorted.indexOfFirst { it.isToday }
        var streak = 0
        val start = if (todayIdx >= 0 && sorted[todayIdx].state == "claimed") todayIdx
                    else if (todayIdx > 0) todayIdx - 1 else -1
        if (start >= 0) {
            for (i in start downTo 0) {
                if (sorted[i].state == "claimed") streak++ else break
            }
        }
        val canClaim = days.any { it.state == "claimable" }
        val claimedToday = days.any { it.isToday && it.state == "claimed" }
        return BuiltInCheckin(days = sorted, streak = streak, canClaim = canClaim, claimedToday = claimedToday)
    }

    override suspend fun fetchCheckin(account: BuiltInAccount): BuiltInCheckin = withContext(Dispatchers.IO) {
        val req = signedDailyRequest(account, "/minimax-cloud/api/v1/signin/status", "GET", null)
        val (code, text) = try {
            request(req, "GET", null)
        } catch (e: Exception) {
            return@withContext BuiltInCheckin(error = "签到状态请求失败：${e.message}")
        }
        if (code !in 200..299) {
            return@withContext BuiltInCheckin(error = "签到状态返回 HTTP $code" + snippet(text))
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext BuiltInCheckin(error = "签到状态响应不是 JSON")
        parsePanel(root)
    }

    override suspend fun claimCheckin(account: BuiltInAccount): BuiltInCheckin = withContext(Dispatchers.IO) {
        val statusReq = signedDailyRequest(account, "/minimax-cloud/api/v1/signin/status", "GET", null)
        val (sCode, sText) = try {
            request(statusReq, "GET", null)
        } catch (e: Exception) {
            return@withContext BuiltInCheckin(error = "签到状态请求失败：${e.message}")
        }
        val statusRoot = runCatching { JSONObject(sText) }.getOrNull()
        if (sCode !in 200..299 || statusRoot == null) {
            return@withContext BuiltInCheckin(error = "签到状态返回 HTTP $sCode" + snippet(sText))
        }
        val accountKey = statusRoot.optString("account_key", "")
            .ifEmpty { statusRoot.optJSONObject("data")?.optString("account_key", "").orEmpty() }
        val claimReq = signedDailyRequest(account, "/minimax-cloud/api/v1/signin/claim", "POST", "{}")
        val (code, text) = try {
            request(claimReq, "POST", "{}")
        } catch (e: Exception) {
            return@withContext BuiltInCheckin(error = "签到请求失败：${e.message}")
        }
        if (code !in 200..299) {
            return@withContext BuiltInCheckin(error = "签到返回 HTTP $code" + snippet(text))
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext BuiltInCheckin(error = "签到响应不是 JSON")
        val result = parsePanel(root)
        val claimResult = root.optInt("claim_result", root.optJSONObject("data")?.optInt("claim_result", 0) ?: 0)
        val points = root.optInt("points", root.optJSONObject("data")?.optInt("points", 0) ?: 0)
        val msg = when (claimResult) {
            1 -> "签到成功，获得 $points 积分"
            2 -> "今天已经领过了"
            else -> if (result.canClaim) "已提交签到请求" else "当前没有可领取的签到"
        }
        result.copy(message = msg)
    }
}
