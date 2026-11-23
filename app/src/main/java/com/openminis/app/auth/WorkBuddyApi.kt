package com.openminis.app.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkBuddy / CodeBuddy (Tencent) upstream protocol.
 *
 * Ported from the open-source WorkBuddy2API-Android gateway (jilin0105 /
 * WorkBuddy2API-Android, MIT) so Minis can offer WorkBuddy as an
 * account-login "built-in model platform": the user signs in with their own
 * WorkBuddy account and the app consumes that account's included model quota
 * through the same OpenAI-compatible `/v2/chat/completions` endpoint the
 * official client uses.
 *
 * This is an UNOFFICIAL integration. It mimics the official CodeBuddy CLI
 * client identity because the upstream applies channel verification to the
 * chat endpoint. Use is subject to the upstream terms of service.
 *
 * Wire facts (verified against the reference project + official desktop app):
 *  - login    : POST {backend}/v2/plugin/auth/state?platform=CLI   -> state + authUrl
 *  - poll     : GET  {backend}/v2/plugin/auth/token?state=...      -> accessToken/domain/...
 *  - profile  : GET  {backend}/v2/plugin/login/account?state=...   -> uid/enterpriseId
 *  - refresh  : POST {backend}/v2/plugin/auth/token/refresh
 *  - models   : GET  {backend}/console/enterprises/personal/models -> models[] + agents[]
 *  - credits  : POST {host}/billing/meter/get-user-resource-summary
 *               POST {host}/billing/meter/get-user-resource-paid-packages   (browser UA)
 *  - checkin  : POST {backend}/v2/billing/meter/daily-checkin             (returns no credits)
 *  - gift     : POST {backend}/billing/meter/claim-gift
 *  - comp     : POST {backend}/billing/meter/claim-compensation
 *  - streak   : GET  {backend}/activity/growth/streak
 *  - chat     : POST {backend}/v2/chat/completions (OpenAI-compatible + channel headers)
 *
 * Everything surfaced in the UI comes from these endpoints — no invented
 * numbers. The check-in endpoint deliberately returns no credit amount, so the
 * only honest way to report "how much did I get today" is a before/after
 * snapshot diff, which [checkin] does.
 */
object WorkBuddyApi {
    private const val TAG = "WorkBuddyApi"

    const val DOMESTIC_BACKEND = "https://copilot.tencent.com"
    const val INTERNATIONAL_BACKEND = "https://www.codebuddy.ai"
    const val DOMESTIC_DOMAIN = "www.codebuddy.cn"
    const val INTERNATIONAL_DOMAIN = "www.codebuddy.ai"
    /** Web origin used by the official desktop billing page (国内). */
    const val DOMESTIC_WEB = "https://www.workbuddy.cn"

    val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---- official client identity (from @tencent-ai/codebuddy-code CLI) ----
    private const val CLI_VERSION = "2.169.0"
    private const val PRODUCT_NAME = "CodeBuddy"
    private const val PLATFORM = "CLI"
    const val CLI_UA = "$PLATFORM/$CLI_VERSION $PRODUCT_NAME/$CLI_VERSION"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** Headers sent before a user is identified (auth state / token polling). */
    fun noAuthHeaders(domain: String): MutableMap<String, String> = mutableMapOf(
        "X-No-Authorization" to "true",
        "X-No-User-Id" to "true",
        "X-No-Enterprise-Id" to "true",
        "X-No-Department-Info" to "true",
        "X-Domain" to domain,
        "User-Agent" to CLI_UA,
    )

    fun pluginHeaders(): Map<String, String> = mapOf(
        "X-Product" to "SaaS",
        "X-Product-Version" to CLI_VERSION,
        "X-IDE-Type" to PLATFORM,
        "X-IDE-Name" to PLATFORM,
        "X-IDE-Version" to CLI_VERSION,
    )

    fun stainlessHeaders(): Map<String, String> = mapOf(
        "X-Stainless-Lang" to "js",
        "X-Stainless-Package-Version" to "6.25.0",
        "X-Stainless-OS" to "Linux",
        "X-Stainless-Arch" to "arm64",
        "X-Stainless-Runtime" to "node",
        "X-Stainless-Runtime-Version" to "v22.11.0",
        "X-Stainless-Retry-Count" to "0",
    )

    /**
     * Base identity headers for an authenticated plugin request.
     * Mirrors the reference `headersFor`: Content-Type/Accept, bearer, X-User-Id
     * (the account uid — REQUIRED by the models endpoint), enterprise/tenant,
     * domain, CLI UA + product identity headers.
     */
    fun authHeaders(
        token: String,
        domain: String,
        enterpriseId: String,
        uid: String,
    ): MutableMap<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer $token",
            "X-Domain" to domain.ifBlank { DOMESTIC_DOMAIN },
            "User-Agent" to CLI_UA,
        )
        if (uid.isNotBlank()) h["X-User-Id"] = uid
        if (enterpriseId.isNotBlank()) {
            h["X-Enterprise-Id"] = enterpriseId
            h["X-Tenant-Id"] = enterpriseId
        }
        h.putAll(pluginHeaders())
        return h
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray((n + 1) / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(n)
    }

    /** Per-request channel-verification + tracing headers for the chat endpoint. */
    fun chatExtraHeaders(conversationId: String): Map<String, String> {
        val messageId = UUID.randomUUID().toString().replace("-", "")
        val conversationRequestId = UUID.randomUUID().toString().replace("-", "")
        val traceId = randomHex(32)
        val spanId = randomHex(16)
        val parentSpanId = randomHex(16)
        return buildMap {
            put("X-Conversation-ID", conversationId)
            put("X-Conversation-Request-ID", conversationRequestId)
            put("X-Conversation-Message-ID", messageId)
            put("X-Request-ID", messageId)
            put("X-Agent-Intent", "craft")
            put("X-Agent-Type", "main")
            putAll(stainlessHeaders())
            put("x-requested-with", "XMLHttpRequest")
            put("X-Agent-Purpose", "conversation")
            put("X-Agent-Runtime", "other")
            put("X-Private-Data", "false")
            put("x-codebuddy-request", "1")
            put("X-Root-Request-ID", messageId)
            put("traceparent", "00-$traceId-$spanId-01")
            put("b3", "$traceId-$spanId-1-$parentSpanId")
            put("X-B3-TraceId", traceId)
            put("X-B3-ParentSpanId", parentSpanId)
            put("X-B3-SpanId", spanId)
            put("X-B3-Sampled", "1")
            put("X-Trace-ID", traceId)
            put(
                "baggage",
                "codebuddy.session_id=$conversationId,codebuddy.conversation_request_id=$conversationRequestId"
            )
        }
    }

    // ---- raw HTTP helpers ----
    private fun request(url: String, method: String, body: JSONObject?, headers: Map<String, String>): Request {
        val b = body?.toString()?.toRequestBody(JSON)
        return Request.Builder().url(url).method(method, b).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
    }

    private data class RawResponse(val code: Int, val text: String)

    private fun execute(url: String, method: String, body: JSONObject?, headers: Map<String, String>): RawResponse {
        val resp = client.newCall(request(url, method, body, headers)).execute()
        val code = resp.code
        val text = resp.body?.string().orEmpty()
        resp.close()
        return RawResponse(code, text)
    }

    /** Unwrap {code,data,msg}; throw with the upstream message when code != 0. */
    private fun unwrap(text: String): JSONObject {
        val root = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        if (root.has("code") && root.optInt("code", -1) != 0) {
            val code = root.optInt("code", -1)
            val msg = root.optString("msg", root.optString("message", "上游请求失败"))
            throw IOException("上游返回错误 $code：$msg")
        }
        val data = root.optJSONObject("data")
        if (data != null) {
            listOf("code", "msg", "message").forEach { k ->
                if (!data.has(k) && root.has(k)) data.put(k, root.get(k))
            }
            return data
        }
        val result = root.optJSONObject("result")
        return result ?: root
    }

    /** Short, human-readable description of a non-2xx body (msg first, raw text as fallback). */
    private fun describeBody(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val msg = runCatching {
            val o = JSONObject(t)
            o.optString("msg", o.optString("message", ""))
        }.getOrDefault("")
        return if (msg.isNotBlank()) "，上游说明「${msg.take(100)}」" else "，响应「${t.take(100)}」"
    }

    data class LoginSession(val region: String, val backend: String, val domain: String, val state: String, val authUrl: String)

    data class Account(
        val accessToken: String,
        val refreshToken: String?,
        val uid: String,
        val domain: String,
        val enterpriseId: String,
        val region: String,
        val backend: String,
        val conversationId: String = UUID.randomUUID().toString(),
        val nickname: String = "",
    )

    // ---- login ----
    suspend fun beginLogin(region: String): LoginSession = withContext(Dispatchers.IO) {
        val backend = if (region == "international") INTERNATIONAL_BACKEND else DOMESTIC_BACKEND
        val domain = if (region == "international") INTERNATIONAL_DOMAIN else DOMESTIC_DOMAIN
        val resp = execute(
            "$backend/v2/plugin/auth/state?platform=CLI",
            "POST", JSONObject(), noAuthHeaders(domain)
        )
        if (resp.code !in 200..299) {
            throw Exception("WorkBuddy 登录请求失败 (HTTP ${resp.code}): ${resp.text.take(160)}")
        }
        val data = unwrap(resp.text)
        val state = data.optString("state", "")
        val url = data.optString("authUrl", "").ifEmpty { data.optString("auth_url", "") }
        if (state.isBlank() || url.isBlank()) {
            throw Exception("WorkBuddy 登录接口未返回 state/authUrl: ${resp.text.take(160)}")
        }
        LoginSession(region, backend, domain, state, url)
    }

    suspend fun pollToken(session: LoginSession): Account? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(session.state, "UTF-8")
        val noAuth = noAuthHeaders(session.domain)
        val tokenResp = execute(
            "${session.backend}/v2/plugin/auth/token?state=$encoded", "GET", null, noAuth
        )
        if (tokenResp.code !in 200..299) return@withContext null
        val token = try { unwrap(tokenResp.text) } catch (_: Exception) { return@withContext null }
        val access = token.optString("accessToken", "").ifEmpty { token.optString("access_token", "") }
        if (access.isBlank()) return@withContext null
        val domain = token.optString("domain", "").ifBlank { session.domain }
        val entId = token.optString("enterpriseId", "")

        val h = noAuth.toMutableMap().apply {
            put("Authorization", "Bearer $access")
            put("X-Domain", domain)
            if (entId.isNotBlank()) {
                put("X-Enterprise-Id", entId)
                put("X-Tenant-Id", entId)
            }
        }
        val profileResp = execute(
            "${session.backend}/v2/plugin/login/account?state=$encoded", "GET", null, h
        )
        val profile = if (profileResp.code in 200..299) {
            runCatching { unwrap(profileResp.text) }.getOrDefault(JSONObject())
        } else JSONObject()
        val uid = profile.optString("uid", "").ifEmpty { token.optString("uid", "") }
        Account(
            accessToken = access,
            refreshToken = token.optString("refreshToken", "").ifEmpty { token.optString("refresh_token", "") }.ifEmpty { null },
            uid = uid,
            domain = domain,
            enterpriseId = entId.ifEmpty { profile.optString("enterpriseId", "") },
            region = session.region,
            backend = session.backend,
            nickname = profile.optString("nickname", "").ifEmpty { profile.optString("username", "") },
        )
    }

    suspend fun refreshAccessToken(account: Account): Account? = withContext(Dispatchers.IO) {
        val h = authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
        val resp = execute(
            "${account.backend}/v2/plugin/auth/token/refresh", "POST", JSONObject(), h
        )
        if (resp.code !in 200..299) return@withContext null
        val data = runCatching { unwrap(resp.text) }.getOrNull() ?: return@withContext null
        val access = data.optString("accessToken", "").ifEmpty { data.optString("access_token", "") }
        if (access.isBlank()) return@withContext null
        account.copy(
            accessToken = access,
            refreshToken = data.optString("refreshToken", "").ifEmpty { account.refreshToken ?: "" }.ifEmpty { null },
            enterpriseId = data.optString("enterpriseId", "").ifBlank { account.enterpriseId },
        )
    }

    // ---- models ----
    data class ModelsResult(val models: List<WorkBuddyModel>, val error: String?, val fetchedAt: Long = System.currentTimeMillis())

    /**
     * One entry of the live catalogue. Every field below is read from the
     * upstream payload — nothing is synthesised, so the list is exactly what
     * this account is entitled to.
     */
    data class WorkBuddyModel(
        val id: String,
        val displayName: String,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        /** Upstream cost multiplier, e.g. 0.79 for "x0.79 credits". null = not labelled. */
        val creditsPerCall: Double? = null,
        val vendor: String? = null,
        val supportsVision: Boolean = false,
        val supportsTools: Boolean = false,
        val supportsReasoning: Boolean = false,
        val onlyReasoning: Boolean = false,
        /** Listed in the CLI agent group (the official "推荐" set). */
        val recommended: Boolean = false,
    )

    /** Fetch the model catalog. Returns an error message (instead of empty) on failure. */
    suspend fun fetchModelsDetailed(account: Account): ModelsResult = withContext(Dispatchers.IO) {
        val h = authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
        // The enterprise-personal catalogue is the authoritative one. A second
        // path is probed only when it yields nothing, because gateway builds
        // differ and a silent empty list would look like "0 models" in the UI.
        val paths = listOf(
            "/console/enterprises/personal/models",
            "/v2/plugin/models",
        )
        var lastError: String? = null
        for (path in paths) {
            val resp = try { execute(account.backend + path, "GET", null, h) } catch (e: Exception) {
                lastError = e.message
                continue
            }
            if (resp.code !in 200..299) {
                Log.w(TAG, "fetchModels $path HTTP ${resp.code}: ${resp.text.take(200)}")
                lastError = "模型接口返回 HTTP ${resp.code}" + describeBody(resp.text)
                continue
            }
            val result = parseModelsResponse(resp.text)
            if (result.models.isNotEmpty()) return@withContext result
            lastError = result.error
        }
        ModelsResult(emptyList(), lastError ?: "模型接口未返回数据")
    }

    /** Parse one models payload, tolerating both {code,data} and bare-array shapes. */
    private fun parseModelsResponse(text: String): ModelsResult {
        return try {
            val bare = runCatching { JSONArray(text) }.getOrNull()
            if (bare != null) {
                val parsed = parseModels(bare, emptySet())
                return if (parsed.isEmpty()) ModelsResult(emptyList(), "模型列表为空")
                else ModelsResult(parsed, null)
            }
            val data = unwrap(text)
            val arr = data.optJSONArray("models")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("data")
                ?: data.optJSONObject("result")?.optJSONArray("models")
                ?: data.optJSONObject("catalog")?.optJSONArray("models")
                ?: return ModelsResult(emptyList(), "模型响应缺少 models 字段")
            val parsed = parseModels(arr, recommendedIds(arr, data))
            if (parsed.isEmpty()) ModelsResult(emptyList(), "账号下没有可用模型")
            else ModelsResult(parsed, null)
        } catch (e: Exception) {
            ModelsResult(emptyList(), e.message ?: "模型解析失败")
        }
    }

    /**
     * The upstream marks its "recommended" set through `agents[]`, where the
     * entry named `cli` carries the model ids the official client shows first.
     * It is a HINT, not a filter: the catalogue itself is the entitlement.
     */
    private fun recommendedIds(models: JSONArray, data: JSONObject): Set<String> {
        val out = linkedSetOf<String>()
        val agents = data.optJSONArray("agents") ?: JSONArray()
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            if (!agent.optString("name").equals("cli", true)) continue
            val ids = agent.optJSONArray("models") ?: JSONArray()
            for (j in 0 until ids.length()) {
                when (val entry = ids.opt(j)) {
                    is JSONObject -> out += entry.optString("id", entry.optString("modelId"))
                    is String -> out += entry
                }
            }
        }
        // Older payloads put the same list on the top level.
        data.optJSONArray("cli_recommended")?.let { arr ->
            for (j in 0 until arr.length()) arr.opt(j)?.toString()?.let(out::add)
        }
        // Fallback: some builds flag the model row itself.
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optBoolean("recommended", false) || m.optBoolean("cliRecommended", false)) {
                out += m.optString("id", m.optString("modelId"))
            }
        }
        out.remove("")
        return out
    }

    /** Convenience wrapper used by the model-refresh path. */
    suspend fun fetchModels(account: Account): List<WorkBuddyModel> = fetchModelsDetailed(account).models

    private fun parseModels(models: JSONArray, recommended: Set<String>): List<WorkBuddyModel> {
        val out = mutableListOf<WorkBuddyModel>()
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            // Different deployments name the identifier differently; the chat
            // request needs the wire id, the UI needs a readable name.
            val id = sequenceOf("id", "modelId", "model", "modelName", "name", "key")
                .map { m.optString(it, "") }
                .firstOrNull { it.isNotBlank() }
                ?: continue
            if (m.optBoolean("disabled", false) || m.optBoolean("isDisabled", false)) continue
            if (m.has("enabled") && !m.optBoolean("enabled", true)) continue
            val name = sequenceOf("displayName", "name", "title", "label")
                .map { m.optString(it, "") }
                .firstOrNull { it.isNotBlank() } ?: id
            val reasoning = m.optJSONObject("reasoning") ?: JSONObject()
            val vision = (m.optBoolean("supportsImages", false) || m.optBoolean("supportsVision", false)) &&
                !m.optBoolean("disabledMultimodal", false)
            out += WorkBuddyModel(
                id = id,
                displayName = name,
                contextWindow = sequenceOf("maxInputTokens", "contextWindow", "contextLength", "maxContextTokens")
                    .map { m.optInt(it, 0) }
                    .firstOrNull { it > 0 },
                maxOutputTokens = sequenceOf("maxOutputTokens", "maxOutputLength")
                    .map { m.optInt(it, 0) }
                    .firstOrNull { it > 0 },
                creditsPerCall = parseCredits(m.opt("credits")),
                vendor = m.optString("vendor", "").ifBlank { null },
                supportsVision = vision,
                supportsTools = m.optBoolean("supportsToolCall", false) || m.optBoolean("supportsTools", false),
                supportsReasoning = reasoning.optBoolean("supportsReasoning", m.optBoolean("supportsReasoning", false)),
                onlyReasoning = reasoning.optBoolean("onlyReasoning", m.optBoolean("onlyReasoning", false)),
                recommended = recommended.contains(id),
            )
        }
        // Dedupe on id, keeping the first (upstream sometimes repeats a model
        // per vendor prefix).
        return out.distinctBy { it.id }
    }

    private fun parseCredits(raw: Any?): Double? {
        if (raw == null) return null
        val token = raw.toString().trim().removePrefix("x").removePrefix("X").trim()
            .substringBefore(" ").substringBefore("credits").trim()
        return token.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    // ---- credits ----
    data class CreditPackage(
        val name: String,
        val total: Double,
        val remaining: Double,
        val expireAt: Long? = null,
    )

    data class Credits(
        val remaining: Double?,
        val total: Double?,
        val expireAt: Long? = null,
        val packages: List<CreditPackage> = emptyList(),
        val error: String? = null,
    )

    /**
     * Query the account's quota. Mirrors the reference `fetchCredits`:
     * the desktop personal-usage endpoints are
     *   /billing/meter/get-user-resource-summary        (summary)
     *   /billing/meter/get-user-resource-paid-packages  (paged)
     * both POST, with a BROWSER UA (the gateway WAF blocks non-browser UAs on
     * billing). Hosts are probed backend-then-web-domain. Field names are the
     * upstream PascalCase ones (CycleTotalCapacity / CycleRemainCapacity, or
     * CapacitySize / CapacityRemain for non-cyclic packages).
     */
    suspend fun fetchCredits(account: Account): Credits = withContext(Dispatchers.IO) {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer ${account.accessToken}",
            "X-Domain" to account.domain.ifBlank { DOMESTIC_DOMAIN },
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://${account.domain}/",
        ).apply {
            if (account.uid.isNotBlank()) put("X-User-Id", account.uid)
            if (account.enterpriseId.isNotBlank()) {
                put("X-Enterprise-Id", account.enterpriseId)
                put("X-Tenant-Id", account.enterpriseId)
            }
        }
        val hosts = linkedSetOf(account.backend, "https://${account.domain}")
        val probes = listOf(
            "/billing/meter/get-user-resource-summary" to JSONObject(),
            "/billing/meter/get-user-resource-paid-packages" to
                JSONObject().put("PageNumber", 1).put("PageSize", 100),
        )
        var lastError: String? = null
        for (host in hosts) {
            for ((path, body) in probes) {
                val resp = try { execute(host + path, "POST", body, h) } catch (e: Exception) {
                    lastError = e.message; continue
                }
                if (resp.code !in 200..299) { lastError = "额度接口返回 HTTP ${resp.code}" + describeBody(resp.text); continue }
                val root = try { JSONObject(resp.text) } catch (_: Exception) { continue }
                val packages = extractPackages(root)
                if (packages.length() > 0) {
                    return@withContext aggregateCredits(packages)
                }
            }
        }
        Credits(null, null, error = lastError ?: "额度接口未返回数据")
    }

    private fun extractPackages(root: JSONObject): JSONArray {
        val data = root.optJSONObject("data") ?: root
        data.optJSONArray("Packages")?.let { if (it.length() > 0) return it }
        data.optJSONObject("Response")?.optJSONObject("Data")?.optJSONArray("Packages")
            ?.let { if (it.length() > 0) return it }
        // Summary shape: accounts / packages arrays under various keys.
        for (key in listOf("Accounts", "accounts", "packages", "PackageList")) {
            data.optJSONArray(key)?.let { if (it.length() > 0) return it }
        }
        // Summary may itself be one object describing the total.
        for (key in listOf("Summary", "summary", "UserResource", "UserResourceSummary")) {
            data.optJSONObject(key)?.let { return JSONArray().put(it) }
        }
        // Flat summary: the payload itself carries the capacity fields.
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.contains("Capacity", ignoreCase = true) || k.contains("Credit", ignoreCase = true)) {
                if (!data.isNull(k) && data.optDouble(k, Double.NaN).isFinite()) {
                    return JSONArray().put(data)
                }
            }
        }
        return JSONArray()
    }

    private fun aggregateCredits(packages: JSONArray): Credits {
        var remain = 0.0
        var total = 0.0
        var expire: Long? = null
        val list = mutableListOf<CreditPackage>()
        for (i in 0 until packages.length()) {
            val a = packages.optJSONObject(i) ?: continue
            val cycle = firstDouble(a, "CycleTotalCapacity", "CycleCapacitySize", "CycleTotalCredit")
            val r = if (cycle > 0) firstDouble(a, "CycleRemainCapacity", "CycleCapacityRemain", "CycleRemainCredit")
                    else firstDouble(a, "RemainCapacity", "CapacityRemain", "RemainCredit", "Remain")
            val t = if (cycle > 0) cycle else firstDouble(a, "TotalCapacity", "CapacitySize", "TotalCredit", "Total")
            remain += r; total += t
            val rawExpiry = listOf("ExpireTime", "PackageEndTime", "EndTime", "CycleEndTime")
                .firstNotNullOfOrNull { k -> a.opt(k)?.toString()?.toDoubleOrNull() }
            val sec = rawExpiry?.takeIf { it > 0 }?.let {
                if (it > 10_000_000_000L) (it / 1000).toLong() else it.toLong()
            }
            if (sec != null) expire = expire?.coerceAtMost(sec) ?: sec
            if (t > 0 || r > 0) {
                list += CreditPackage(
                    name = sequenceOf("PackageName", "Name", "Title", "ResourceName", "CapacityName", "Type")
                        .map { a.optString(it, "") }
                        .firstOrNull { it.isNotBlank() }
                        ?: if (cycle > 0) "周期额度" else "资源包",
                    total = t,
                    remaining = r,
                    expireAt = sec,
                )
            }
        }
        return Credits(
            remaining = if (remain != 0.0 || total != 0.0) remain else null,
            total = if (total != 0.0) total else null,
            expireAt = expire,
            packages = list,
        )
    }

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val v = obj.optDouble(k, Double.NaN)
            if (!v.isNaN()) return v
        }
        return 0.0
    }

    // ---- check-in ----
    data class CheckinResult(
        val alreadyChecked: Boolean,
        val message: String,
        /** Snapshot diff around the check-in; null when the diff can't be computed. */
        val gained: Double?,
        val credits: Credits?,
    )

    /** One probed endpoint, kept so failures can be described in full. */
    private data class Attempt(
        val label: String,
        val status: Int,
        val body: String,
    ) {
        val code: Int? get() = runCatching { JSONObject(body).optInt("code", -999) }.getOrNull()?.takeIf { it != -999 }
        val upstreamMsg: String get() = runCatching {
            val o = JSONObject(body)
            o.optString("msg", o.optString("message", ""))
        }.getOrDefault("")
        val oneLine: String
            get() {
                val parts = mutableListOf("$label → HTTP $status")
                code?.let { parts += "业务码 $it" }
                upstreamMsg.takeIf { it.isNotBlank() }?.let { parts += "上游说明「${it.take(90)}」" }
                if (code == null && upstreamMsg.isBlank() && body.isNotBlank()) {
                    parts += "响应片段「${body.replace(Regex("\\s+"), " ").take(90)}」"
                }
                return parts.joinToString("，")
            }
    }

    private fun detailOf(action: String, attempts: List<Attempt>, hint: String): String = buildString {
        append(action).append("没有成功。")
        if (attempts.isEmpty()) {
            append("\n没有收到任何响应（网络层就失败了）。")
        } else {
            append("\n依次尝试了 ").append(attempts.size).append(" 个接口，结果如下：")
            attempts.forEach { append("\n· ").append(it.oneLine) }
        }
        append("\n").append(hint)
    }

    /** Console-style headers: what the official web console sends for billing actions. */
    private fun webHeaders(account: Account): MutableMap<String, String> = mutableMapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer ${account.accessToken}",
        "X-Domain" to account.domain.ifBlank { DOMESTIC_DOMAIN },
        "User-Agent" to BROWSER_UA,
        "Origin" to "https://${account.domain}",
        "Referer" to "https://${account.domain}/",
    ).apply {
        if (account.uid.isNotBlank()) put("X-User-Id", account.uid)
        if (account.enterpriseId.isNotBlank()) {
            put("X-Enterprise-Id", account.enterpriseId)
            put("X-Tenant-Id", account.enterpriseId)
        }
    }

    /**
     * Daily check-in. The endpoint itself returns NO credit amount (it only
     * "punches the card"), so we snapshot the balance before and after and
     * report the difference — that is the only truthful "you got N today".
     *
     * The endpoint has moved between gateway builds (/v2/billing/... vs
     * /billing/..., plugin host vs web host), so all known shapes are probed
     * in order and the first one the account accepts wins. If none works, the
     * thrown message lists every attempt with its HTTP status, upstream code
     * and upstream text — no bare "HTTP 400".
     */
    suspend fun checkin(account: Account, beforeRemain: Double?): CheckinResult = withContext(Dispatchers.IO) {
        // The action has taken several shapes across gateway builds: plugin vs
        // web identity, /v2 vs bare /billing path, empty body vs a timezone
        // field. Probe the known shapes in order and take the first answer the
        // account accepts. Deliberately short: a burst of failing POSTs to a
        // billing endpoint looks like abuse.
        data class Probe(val label: String, val url: String, val web: Boolean, val body: JSONObject)

        val webUrl = "https://${account.domain}"
        val probes = listOf(
            Probe("插件身份 · /v2/billing/meter/daily-checkin", "${account.backend}/v2/billing/meter/daily-checkin", false, JSONObject()),
            Probe("网页身份 · /v2/billing/meter/daily-checkin", "${account.backend}/v2/billing/meter/daily-checkin", true, JSONObject()),
            Probe("网页身份 · $webUrl/billing/meter/daily-checkin", "$webUrl/billing/meter/daily-checkin", true,
                JSONObject().put("timezone", "Asia/Shanghai")),
            Probe("插件身份 · /billing/meter/daily-checkin", "${account.backend}/billing/meter/daily-checkin", false,
                JSONObject().put("timezone", "Asia/Shanghai")),
        )
        val attempts = mutableListOf<Attempt>()
        for (probe in probes) {
            val headers = if (probe.web) webHeaders(account)
            else authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
                .apply { put("User-Agent", BROWSER_UA) }
            val resp = try {
                execute(probe.url, "POST", probe.body, headers)
            } catch (e: Exception) {
                attempts += Attempt(probe.label, 0, e.message.orEmpty())
                continue
            }
            val root = runCatching { JSONObject(resp.text) }.getOrDefault(JSONObject())
            val msg = root.optString("msg", root.optString("message", ""))
            val already = msg.contains("已签到") || msg.contains("already", true)
            val code = if (resp.code in 200..299) root.optInt("code", 0) else -1
            if (already) {
                val after = runCatching { fetchCredits(account) }.getOrNull()
                return@withContext CheckinResult(true, "今日已签到", null, after)
            }
            if (resp.code !in 200..299 || code != 0) {
                attempts += Attempt(probe.label, resp.code, resp.text)
                continue
            }
            val after = runCatching { fetchCredits(account) }.getOrNull()
            val gained = if (beforeRemain != null && after?.remaining != null) {
                (after.remaining - beforeRemain).takeIf { it > 0 }
            } else null
            return@withContext CheckinResult(
                alreadyChecked = false,
                message = "签到成功",
                gained = gained,
                credits = after,
            )
        }
        throw IOException(
            detailOf(
                action = "每日签到",
                attempts = attempts,
                hint = "可能原因：该账号今天已经签到过（部分版本用报错表示）、登录凭证过期、账号未开通签到活动、或上游这一版换了接口形态。" +
                    "\n建议：先点「刷新数据」确认登录仍有效，再点「重新登录」换一张新凭证重试；仍然失败时，请把上面这段文字完整发给我，我按业务码和上游说明定位。",
            )
        )
    }

    // ---- one-off rewards ----
    /**
     * Claim the new-user gift / an activity compensation package. Both are
     * idempotent server-side: a second call returns credit 0 (or an
     * "already claimed" message), which we surface as 0 rather than an error.
     */
    suspend fun claimReward(account: Account, kind: String): Double? = withContext(Dispatchers.IO) {
        val (action, barePath) = when (kind) {
            "gift" -> "领取新手礼包" to "/billing/meter/claim-gift"
            "compensation" -> "领取活动补偿" to "/billing/meter/claim-compensation"
            else -> return@withContext null
        }
        val candidates = listOf(
            account.backend + barePath to false,
            account.backend + barePath to true,
            "https://${account.domain}$barePath" to true,
            account.backend + "/v2$barePath" to false,
        )
        val attempts = mutableListOf<Attempt>()
        for ((url, web) in candidates) {
            val headers = if (web) webHeaders(account)
            else authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
            val resp = try {
                execute(url, "POST", JSONObject(), headers)
            } catch (e: Exception) {
                attempts += Attempt(url, 0, e.message.orEmpty())
                continue
            }
            if (resp.code !in 200..299) {
                val a = Attempt(url, resp.code, resp.text)
                if (a.upstreamMsg.contains("已领取") || a.upstreamMsg.contains("already", true) ||
                    a.upstreamMsg.contains("已领完") || a.upstreamMsg.contains("不存在")
                ) {
                    return@withContext 0.0
                }
                attempts += a
                continue
            }
            val root = runCatching { JSONObject(resp.text) }.getOrDefault(JSONObject())
            val code = root.optInt("code", 0)
            val msg = root.optString("msg", root.optString("message", ""))
            if (code != 0 || msg.contains("已领取") || msg.contains("already", true)) {
                if (msg.contains("已领取") || msg.contains("already", true)) return@withContext 0.0
                attempts += Attempt(url, resp.code, resp.text)
                continue
            }
            val data = runCatching { unwrap(resp.text) }.getOrDefault(root)
            return@withContext firstDouble(data, "credit", "credits", "amount").takeIf { it > 0 }
        }
        throw IOException(
            detailOf(
                action = action,
                attempts = attempts,
                hint = "可能原因：这个礼包/补偿不在当前账号的发放范围内（很常见，不代表出错）、登录凭证过期、或接口改版。" +
                    "\n建议：这类福利是活动定向发放的，账号没有资格时上游会直接拒绝；先点「刷新数据」确认登录仍有效，再试一次。",
            )
        )
    }

    // ---- growth (read-only) ----
    data class Streak(
        val current: Int,
        val longest: Int?,
        val monthDays: Int?,
        val makeupCards: Int?,
        val raw: String? = null,
    )

    /**
     * Consecutive check-in days. Only shown when the endpoint answers — the
     * screen stays quiet otherwise rather than printing a fabricated streak.
     */
    suspend fun fetchStreak(account: Account): Streak? = withContext(Dispatchers.IO) {
        val h = authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
        val resp = try {
            execute("${account.backend}/activity/growth/streak", "GET", null, h)
        } catch (_: Exception) {
            return@withContext null
        }
        if (resp.code !in 200..299) return@withContext null
        val data = runCatching { unwrap(resp.text) }.getOrNull() ?: return@withContext null
        val current = sequenceOf("streak", "current_streak", "days", "continuous_days")
            .map { data.optInt(it, 0) }
            .firstOrNull { it > 0 }
        if (current == null) return@withContext null
        Streak(
            current = current,
            longest = sequenceOf("longest", "max_streak", "longest_streak")
                .map { data.optInt(it, 0) }.firstOrNull { it > 0 },
            monthDays = sequenceOf("month_days", "month_days_count", "checked_days")
                .map { data.optInt(it, 0) }.firstOrNull { it > 0 },
            makeupCards = data.optJSONObject("makeup_cards")?.optInt("balance", 0),
        )
    }

    data class DayCell(val date: String, val score: Int)

    /**
     * Check-in calendar (one cell per day, `score` = credits earned that day).
     * Used only as a visual proof of real activity data; when the endpoint
     * answers with an unexpected shape we return null and the UI omits it.
     */
    suspend fun fetchHeatmap(account: Account): List<DayCell>? = withContext(Dispatchers.IO) {
        val h = authHeaders(account.accessToken, account.domain, account.enterpriseId, account.uid)
        val resp = try {
            execute("${account.backend}/activity/growth/heatmap", "GET", null, h)
        } catch (_: Exception) {
            return@withContext null
        }
        if (resp.code !in 200..299) return@withContext null
        val data = runCatching { unwrap(resp.text) }.getOrNull() ?: return@withContext null
        val arr = data.optJSONArray("heatmap")
            ?: data.optJSONArray("cells")
            ?: data.optJSONArray("days")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("data")
            ?: firstArray(data)
            ?: return@withContext null
        val out = mutableListOf<DayCell>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val date = c.optString("date", "").ifBlank { c.optString("day", "") }.take(10)
            if (date.length < 10) continue
            out += DayCell(date, c.optInt("score", c.optInt("credits", 0)))
        }
        out.sortedBy { it.date }.takeIf { it.isNotEmpty() }
    }

    private fun firstArray(obj: JSONObject): JSONArray? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            obj.optJSONArray(keys.next())?.let { if (it.length() > 0) return it }
        }
        return null
    }
}
