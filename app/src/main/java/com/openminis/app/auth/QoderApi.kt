package com.openminis.app.auth

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

/**
 * [T-builtin-accounts] Qoder (qoder.com) account platform.
 *
 * Ported from the open-source Qoder2Api proxy (ErfanBagheri404, MIT). Qoder's
 * desktop client logs in with a PKCE device flow and then talks to a plain
 * OpenAI-compatible endpoint; only the model catalogue goes through the
 * "COSY" signed envelope (RSA + AES-128-CBC + MD5).
 *
 *  login  : GET  https://qoder.com/device/selectAccounts?challenge=…  (browser)
 *  poll   : GET  https://openapi.qoder.sh/api/v1/deviceToken/poll?nonce=…&verifier=…
 *  refresh: POST https://openapi.qoder.sh/api/v1/deviceToken/refresh
 *  user   : GET  https://openapi.qoder.sh/api/v1/userinfo
 *  plan   : GET  https://openapi.qoder.sh/api/v2/user/plan
 *  quota  : GET  https://openapi.qoder.sh/api/v2/quota/usage
 *  models : GET  https://api3.qoder.sh/algo/api/v2/model/list?Encode=1   (COSY signed)
 *  chat   : POST {GATEWAY}/model/v1/chat/completions  (plain, Bearer)
 *           CN gateway.qoder.com.cn/qsvr · INTL api2.qoder.sh/qsvr
 *
 * UNOFFICIAL integration: subject to Qoder's terms; the private protocol may
 * change with any client release.
 */
object QoderApi : BuiltInPlatformApi {
    override val displayName: String = "Qoder"

    /**
     * Qoder runs two independent sites: qoder.cn (China) and qoder.com
     * (international). Same PKCE device-authorisation protocol, different site
     * host, and the accounts / tokens are not interchangeable — an account from
     * one site simply has no models on the other, so both are exposed.
     *
     * Device-authorisation endpoints are pinned by the official CLI 1.1.94:
     * the browser page is on the site host, while poll / refresh live on the
     * matching openApi host. 404 from poll is the CLI's documented "pending"
     * response, NOT a wrong host.
     */
    override val regions: List<BuiltInRegion> = listOf(BuiltInRegion.CN, BuiltInRegion.INTL)

    private const val TAG = "QoderApi"

    // Endpoint table copied verbatim from the official CLI (@qoder-ai/qodercli
    // 1.1.94, `gHn` in the bundle), one row per site. Using the wrong row is
    // what makes device login poll forever: qoder.com / qoder.cn serve the
    // device-token API, the openapi.* hosts do NOT (they answer 404).
    //
    //   site   website            openApi                        gateway
    //   cn     https://qoder.cn   https://openapi.qoder.com.cn   https://gateway.qoder.com.cn/qsvr
    //   intl   https://qoder.com  https://openapi.qoder.sh      https://api2.qoder.sh/qsvr
    const val WEBSITE_CN = "https://qoder.cn"
    const val WEBSITE_INTL = "https://qoder.com"
    const val OPENAPI_CN = "https://openapi.qoder.com.cn"
    const val OPENAPI_INTL = "https://openapi.qoder.sh"
    const val GATEWAY_CN = "https://gateway.qoder.com.cn/qsvr"
    const val GATEWAY_INTL = "https://api2.qoder.sh/qsvr"

    const val CLIENT_ID = "732aef47-9cf2-46a2-95fe-4cebb5d0d1fa"
    const val REDIRECT_URI = "qoder-app://"
    const val UA = "Qoder/1.1.94 (Windows; x64) node/20"
    private const val COSY_VERSION = "1.1.72"
    /** Version the official CLI 1.1.94 sends as Cosy-Version. */
    private const val CLIENT_VERSION = "1.1.94"
    private const val MODEL_LIST_URL = "https://api3.qoder.sh/algo/api/v2/model/list?Encode=1"

    private fun isIntl(region: String) = region == BuiltInRegion.INTL.id

    /** Device-authorisation host — also the host that serves the poll API. */
    private fun authBase(region: String): String =
        if (isIntl(region)) WEBSITE_INTL else WEBSITE_CN

    private fun openApiBase(region: String): String =
        if (isIntl(region)) OPENAPI_INTL else OPENAPI_CN

    private fun gatewayBase(region: String): String =
        if (isIntl(region)) GATEWAY_INTL else GATEWAY_CN

    /** OpenAI-compatible chat base for a region — used by ProviderFactory. */
    fun chatBase(region: String): String = gatewayBase(region) + "/model/v1"

    /** Default (domestic) chat base for manual provider setup / debug UI. */
    val DEFAULT_CHAT_BASE: String = GATEWAY_CN + "/model/v1"

    /** Site a request belongs to, taken from the account that owns the token. */
    fun siteOf(account: BuiltInAccount): String =
        if (isIntl(account.region)) "qoder.com" else "qoder.cn"

    /** Gateway identity key pinned by the official client. */
    private const val COSY_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc" +
            "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l" +
            "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17" +
            "XcW+ML9FoCI6AOvOzwIDAQAB"

    private val MODELS_ALIAS = mapOf(
        "qwen3.8-flash" to "qwen-flash",
        "qwen3.7-plus" to "qmodel",
        "kimi-k2.7-code" to "kmodel",
        "deepseek-v4-pro" to "dmodel",
        "minimax-m2.5" to "mmodel",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun randomAscii(n: Int): String {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '.', '_', '~')
        val rnd = SecureRandom()
        return (1..n).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun execute(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (method == "POST") rb.post(okhttp3.RequestBody.create(null, body ?: "{}"))
        else rb.get()
        client.newCall(rb.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    // ---- login ----
    override suspend fun beginLogin(region: String): BuiltInLoginStart = withContext(Dispatchers.IO) {
        val verifier = randomAscii(64)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val machineId = UUID.randomUUID().toString()
        val url = authBase(region) + "/device/selectAccounts?" + listOf(
            "challenge" to challengeFor(verifier),
            "challenge_method" to "S256",
            "nonce" to nonce,
            "machine_id" to machineId,
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        BuiltInLoginStart(
            authUrl = url,
            session = BuiltInAccount(
                platform = "qoder",
                accessToken = "",
                refreshToken = null,
                uid = "",
                region = if (region == BuiltInRegion.INTL.id) BuiltInRegion.INTL.id else BuiltInRegion.CN.id,
                extra = mapOf("verifier" to verifier, "nonce" to nonce, "machineId" to machineId),
            ),
        )
    }

    /**
     * Last non-fatal note from [pollLogin] ("waiting" vs a hard error), so the UI
     * can explain a spinner that is not actually progressing.
     */
    @Volatile
    var lastPollNote: String? = null
        private set

    override val pollNote: String? get() = lastPollNote

    override suspend fun pollLogin(state: BuiltInAccount): BuiltInAccount? = withContext(Dispatchers.IO) {
        val nonce = state.extra["nonce"].orEmpty()
        val verifier = state.extra["verifier"].orEmpty()
        if (nonce.isBlank() || verifier.isBlank()) {
            lastPollNote = "登录会话缺少 nonce/verifier，请重新发起登录"
            return@withContext null
        }
        // Official CLI: poll on openApiBaseUrl, 404 means "pending".
        val url = openApiBase(state.region) + "/api/v1/deviceToken/poll?" + listOf(
            "nonce" to nonce, "verifier" to verifier, "challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val (code, text) = try {
            execute(url, "GET", null, mapOf("User-Agent" to UA, "Accept" to "application/json"))
        } catch (e: Exception) {
            lastPollNote = "轮询请求失败：${e.message}"
            Log.w(TAG, "poll error: ${e.message}")
            return@withContext null
        }
        if (code == 404) {
            // qxl = 404 in the official CLI's OAuth client: still pending.
            lastPollNote = "等待浏览器完成授权（HTTP 404 = 官方约定的 pending）"
            return@withContext null
        }
        if (code == 401) {
            lastPollNote = "等待浏览器完成授权（HTTP 401）"
            return@withContext null
        }
        if (code !in 200..299) {
            lastPollNote = "授权接口返回 HTTP $code" + upstreamSnippet(text)
            return@withContext null
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: run {
            lastPollNote = "授权接口返回的不是 JSON"
            return@withContext null
        }
        val data = root.optJSONObject("data") ?: root
        val token0 = data.optString("token", "")
            .ifEmpty { data.optString("access_token", "") }
            .ifEmpty { data.optString("accessToken", "") }
        if (token0.isBlank()) {
            lastPollNote = "等待浏览器完成授权"
            return@withContext null
        }
        lastPollNote = null
        val user = data.optJSONObject("user") ?: JSONObject()
        BuiltInAccount(
            platform = "qoder",
            accessToken = token0,
            refreshToken = data.optString("refresh_token", "")
                .ifEmpty { data.optString("refreshToken", "") }.ifEmpty { null },
            uid = user.optString("uid", "").ifEmpty { data.optString("uid", "") },
            nickname = user.optString("name", "")
                .ifEmpty { user.optString("nickname", "") }
                .ifEmpty { user.optString("email", "") },
            expiresAt = parseEpoch(data.opt("expires_at") ?: data.opt("expiresAt")),
            region = state.region,
            extra = mapOf("machineId" to state.extra["machineId"].orEmpty()),
        )
    }

    override suspend fun refresh(account: BuiltInAccount): BuiltInAccount? = withContext(Dispatchers.IO) {
        val rt = account.refreshToken ?: return@withContext null
        val body = JSONObject().put("refresh_token", rt)
        lastPollNote = null
        val (code, text) = try {
            execute(
                openApiBase(account.region) + "/api/v1/deviceToken/refresh", "POST", body.toString(),
                mapOf("Content-Type" to "application/json", "User-Agent" to UA),
            )
        } catch (e: Exception) {
            lastPollNote = "刷新令牌请求失败：${e.message}"
            return@withContext null
        }
        if (code !in 200..299) return@withContext null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val data = root.optJSONObject("data") ?: root
        val token = data.optString("token", "")
            .ifEmpty { data.optString("access_token", "") }
            .ifEmpty { data.optString("accessToken", "") }
        if (token.isBlank()) return@withContext null
        account.copy(
            accessToken = token,
            refreshToken = data.optString("refresh_token", "")
                .ifEmpty { data.optString("refreshToken", "") }.ifEmpty { account.refreshToken },
            expiresAt = parseEpoch(data.opt("expires_at") ?: data.opt("expiresAt")),
        )
    }

    private fun parseEpoch(raw: Any?): Long {
        val s = raw?.toString() ?: return 0L
        val v = s.toDoubleOrNull() ?: return 0L
        return if (v > 10_000_000_000L) v.toLong() else (v * 1000).toLong()
    }

    // ---- COSY signed model catalogue ----
    private fun cosyHeaders(url: String, uid: String, token: String, machineId: String): Map<String, String> {
        val aes = randomAscii(16).toByteArray() // 16 ASCII bytes = key and IV
        val identity = JSONObject()
            .put("uid", uid)
            .put("security_oauth_token", token)
            .put("name", "")
            .put("aid", "")
            .put("email", "")
            .toString()
        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aesCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(aes))
        val info = b64(aesCipher.doFinal(identity.toByteArray()))

        val pub = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(COSY_PUBLIC_KEY, Base64.DEFAULT))
        )
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub)
        val cosyKey = b64(rsa.doFinal(aes))

        val ts = (System.currentTimeMillis() / 1000).toString()
        val payload = JSONObject()
            .put("version", "v1")
            .put("requestId", UUID.randomUUID().toString())
            .put("info", info)
            .put("cosyVersion", COSY_VERSION)
            .put("ideVersion", "")
            .toString()
        val payloadB64 = b64(payload.toByteArray())

        val path = url.toHttpUrlPath().removePrefix("/algo")
        val body = ""
        val sig = md5Hex(listOf(payloadB64, cosyKey, ts, body, path).joinToString("\n"))
        val mid = machineId.ifBlank { UUID.randomUUID().toString() }
        return mapOf(
            "Authorization" to "Bearer COSY.$payloadB64.$sig",
            "Cosy-Key" to cosyKey,
            "Cosy-User" to uid,
            "Cosy-Date" to ts,
            "Cosy-Version" to COSY_VERSION,
            "Cosy-Machineid" to mid,
            "Cosy-Machinetoken" to mid,
            "Cosy-Machinetype" to "5",
            "Cosy-Machineos" to "x86_64_windows",
            "Cosy-Clienttype" to "5",
            "Cosy-Clientip" to "127.0.0.1",
            "Cosy-Bodyhash" to md5Hex(body),
            "Cosy-Bodylength" to "0",
            "Cosy-Sigpath" to path,
            "Cosy-Data-Policy" to "disagree",
            "Cosy-Organization-Id" to "",
            "Cosy-Organization-Tags" to "",
            "Login-Version" to "v2",
            "X-Request-Id" to UUID.randomUUID().toString(),
            "Accept" to "application/json",
            "User-Agent" to UA,
        )
    }

    private fun String.toHttpUrlPath(): String =
        runCatching { java.net.URL(this).path }.getOrDefault("/")

    /**
     * Official CLI 1.1.94 replaced the COSY-signed /algo/v2 catalogue with a
     * plain Bearer endpoint on the gateway: GET {gateway}/api/v3/model/list
     * ?categories=qcode. The old COSY path now answers 403 "Signature invalid"
     * (confirmed against the live gateway), so this is the primary source.
     *
     * Response shape (parsed by the CLI's Sut/mFl helpers):
     *   { "qcode": [ { key, display_name, enable, is_vl, is_reasoning,
     *                  max_input_tokens, max_output_tokens,
     *                  default_context_window, context_config{...token_count},
     *                  thinking_config{...} } ] }
     *
     * A machine-generated X-Request-Id / Cosy-ClientType / Cosy-Version header
     * triple is required by the gateway's attribution middleware.
     */
    /**
     * Two live sources, both from the official client:
     *   1) COSY-signed GET api3.qoder.sh/algo/api/v2/model/list?Encode=1
     *      -> `chat` array, the FULL catalog (~10-20 models). This is what the
     *         reference client uses and what the user sees in the Qoder app.
     *   2) Bearer GET {gateway}/api/v3/model/list?categories=qcode
     *      -> `qcode` array, a SHORT catalogue (3 models) used by the CLI.
     * We prefer (1) and fall back to (2); never fabricate a static list.
     */
    override suspend fun fetchModels(account: BuiltInAccount): BuiltInModels = withContext(Dispatchers.IO) {
        val fromV2 = fetchModelsV2(account)
        if (fromV2.models.isNotEmpty()) return@withContext fromV2
        val fromV3 = fetchModelsV3(account)
        if (fromV3.models.isNotEmpty()) {
            return@withContext BuiltInModels(
                fromV3.models,
                // Keep the v2 note visible if it failed, so the user knows why
                // only a short list is shown.
                fromV2.error?.let { "全量目录暂不可用，以下为精简目录。\n$it" },
            )
        }
        // Both failed: show the more informative error.
        BuiltInModels(emptyList(), fromV2.error ?: fromV3.error ?: "两个模型目录都不可用")
    }

    /** Full catalogue, COSY-signed GET — the reference client's source.
     *  Tries every known algo host for the region: a CN token must hit a CN
     *  host, an INTL token an INTL host; picking the wrong one answers 403 and
     *  used to silently drop us back to the 3-model catalogue. */
    private fun fetchModelsV2(account: BuiltInAccount): BuiltInModels {
        val candidates = if (isIntl(account.region)) listOf(
            "https://api3.qoder.sh/algo/api/v2/model/list?Encode=1",
            "https://gateway.qoder.com.cn/algo/api/v2/model/list?Encode=1",
        ) else listOf(
            "https://gateway.qoder.com.cn/algo/api/v2/model/list?Encode=1",
            "https://api3.qoder.sh/algo/api/v2/model/list?Encode=1",
        )
        var lastErr: String? = null
        for (url in candidates) {
            val r = fetchModelsV2From(url, account)
            if (r.models.isNotEmpty()) return r
            lastErr = r.error
        }
        return BuiltInModels(emptyList(), lastErr ?: "全量模型目录不可用")
    }

    /** COSY signing requires a real uid. If login didn't give us one, fetch it
     *  from /api/v1/userinfo — exactly what the reference client does. Without
     *  this the signed full catalogue returns 403 and we fall back to 3 models. */
    private fun resolveUid(account: BuiltInAccount): String {
        account.uid.ifBlank { account.extra["uid"].orEmpty() }.takeIf { it.isNotBlank() }?.let { return it }
        val url = openApiBase(account.region) + "/api/v1/userinfo"
        val (code, text) = try {
            execute(url, "GET", null, mapOf(
                "Authorization" to "Bearer ${account.accessToken}",
                "Accept" to "application/json", "User-Agent" to UA,
            ))
        } catch (e: Exception) { return "" }
        if (code !in 200..299) return ""
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        val d = root.optJSONObject("data") ?: root
        return d.optString("id", "")
            .ifEmpty { d.optString("uid", "") }
            .ifEmpty { d.optString("user_id", "") }
    }

    private fun fetchModelsV2From(url: String, account: BuiltInAccount): BuiltInModels {
        val uid = resolveUid(account)
        if (uid.isBlank()) {
            return BuiltInModels(emptyList(), "全量模型目录需要账号 uid，但用户信息接口没有返回（令牌或站点可能不匹配）")
        }
        val mid = account.extra["machineId"].orEmpty().ifBlank { UUID.randomUUID().toString() }
        val headers = cosyHeaders(url, uid, account.accessToken, mid).toMutableMap()
        headers["User-Agent"] = UA
        val (code, text) = try {
            execute(url, "GET", null, headers)
        } catch (e: Exception) {
            return BuiltInModels(emptyList(), "全量模型目录请求失败：${e.message}")
        }
        if (code !in 200..299) {
            return BuiltInModels(
                emptyList(),
                "全量模型目录返回 HTTP $code" + upstreamSnippet(text) +
                    "\n可能原因：账号 uid 缺失、令牌与站点不匹配，或官方签名协议更新。",
            )
        }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return BuiltInModels(emptyList(), "全量模型目录响应不是 JSON")
        val arr = root.optJSONArray("chat")
            ?: root.optJSONArray("qcode")
            ?: root.optJSONArray("models")
            ?: return BuiltInModels(emptyList(), "全量模型目录响应缺少 chat 字段" + upstreamSnippet(text))
        val out = parseModelArray(arr)
        return BuiltInModels(out.distinctBy { it.id }, if (out.isEmpty()) "全量目录里没有启用的模型" else null)
    }

    /** Short CLI catalogue, Bearer GET on the regional gateway. Try a few
     *  category views and keep whichever returns the most models. */
    private fun fetchModelsV3(account: BuiltInAccount): BuiltInModels {
        val headers = mapOf(
            "Authorization" to "Bearer ${account.accessToken}",
            "Accept" to "application/json",
            "User-Agent" to UA,
            "Cosy-ClientType" to "13",
            "Cosy-Version" to CLIENT_VERSION,
            "X-Request-Id" to UUID.randomUUID().toString(),
        )
        val urls = listOf(
            gatewayBase(account.region) + "/api/v3/model/list?categories=qcode",
            gatewayBase(account.region) + "/api/v3/model/list",
        )
        var best = emptyList<BuiltInModel>()
        var lastErr: String? = null
        for (url in urls) {
            val (code, text) = try {
                execute(url, "GET", null, headers)
            } catch (e: Exception) {
                lastErr = "精简模型目录请求失败：${e.message}"
                continue
            }
            if (code !in 200..299) {
                lastErr = "模型目录返回 HTTP $code" + upstreamSnippet(text) +
                    "\n可能原因：令牌与所选站点不匹配，或官方客户端协议更新。"
                continue
            }
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val arr = root.optJSONArray("qcode")
                ?: root.optJSONArray("chat")
                ?: root.optJSONArray("models") ?: continue
            val parsed = parseModelArray(arr).distinctBy { it.id }
            if (parsed.size > best.size) best = parsed
        }
        return if (best.isNotEmpty()) BuiltInModels(best)
        else BuiltInModels(emptyList(), lastErr ?: "模型目录没有可用模型")
    }

    /** Shared row parser for both catalogues. */
    private fun parseModelArray(arr: org.json.JSONArray): List<BuiltInModel> {
        val out = mutableListOf<BuiltInModel>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optBoolean("enable", true) == false) continue
            val key = m.optString("key", "").ifEmpty { m.optString("id", "") }
            if (key.isBlank()) continue
            var ctx = m.optInt("default_context_window", 0)
            m.optJSONObject("context_config")?.let { cc ->
                val keys = cc.keys()
                while (keys.hasNext()) {
                    val w = cc.optJSONObject(keys.next())?.optInt("token_count", 0) ?: 0
                    if (w > ctx) ctx = w
                }
            }
            if (ctx <= 0) ctx = m.optInt("max_input_tokens", 0)
            val pf = m.optDouble("price_factor", Double.NaN)
            val vision = m.optBoolean("is_vl", false) || m.optBoolean("support_image", false) ||
                m.optBoolean("supports_vision", false)
            val reasoning = m.optBoolean("is_reasoning", false) || m.optBoolean("support_reasoning", false)
            out += BuiltInModel(
                id = key,
                name = m.optString("display_name", "").ifEmpty { key },
                vendor = m.optString("vendor", "").ifEmpty { m.optString("provider", "").ifEmpty { null } },
                contextWindow = ctx.takeIf { it > 0 },
                maxOutputTokens = m.optInt("max_output_tokens", 0).takeIf { it > 0 },
                vision = vision,
                reasoning = reasoning,
                free = pf == 0.0,
                priceFactor = pf.takeIf { it.isFinite() && pf != 0.0 },
                recommended = m.optBoolean("is_recommended", false) || m.optBoolean("recommended", false),
                tools = m.optBoolean("is_tool", false) || m.optBoolean("support_tools", false) ||
                    m.optBoolean("tool_call", false),
                onlyReasoning = m.optBoolean("only_reasoning", false),
            )
        }
        return out
    }

    private fun upstreamSnippet(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isBlank()) return ""
        val msg = runCatching {
            val o = JSONObject(t)
            o.optString("msg", o.optString("message", ""))
        }.getOrDefault("")
        return if (msg.isNotBlank()) "，上游说明「${msg.take(100)}」" else "，响应「${t.take(100)}」"
    }

    override suspend fun fetchQuota(account: BuiltInAccount): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val open = openApiBase(account.region)
        val auth = mapOf("Authorization" to "Bearer ${account.accessToken}", "User-Agent" to UA, "Accept" to "application/json")
        val rows = mutableListOf<Pair<String, String>>()
        rows += "站点" to siteOf(account)

        // plan payload is FLAT: plan_tier_name / userType / is_paid_plan /
        // expiresAt / plan_name ...  (confirmed from the official CLI login
        // transcript and ref/q2/main.py).
        runCatching { execute("$open/api/v2/user/plan", "GET", null, auth) }.getOrNull()?.let { (code, text) ->
            if (code in 200..299) {
                val d = runCatching { JSONObject(text).optJSONObject("data") ?: JSONObject(text) }.getOrNull()
                if (d != null) {
                    // Preferred, human-friendly keys first.
                    sequenceOf("plan_tier_name", "plan_name", "name", "plan", "tier")
                        .map { d.optString(it, "") }.firstOrNull { it.isNotBlank() }
                        ?.let { rows += "套餐" to prettyValue("plan", it) }
                    d.opt("expiresAt")?.let { rows += "到期" to prettyDate(it.toString()) }
                    d.opt("is_paid_plan")?.let { rows += "付费类型" to if (it == true || "$it" == "true") "付费订阅" else "免费版" }
                    // Remaining scalar fields, with known keys translated.
                    val dk = d.keys()
                    while (dk.hasNext()) {
                        val k = dk.next()
                        val label = quotaLabel(k) ?: continue
                        val v = d.opt(k)
                        val value = when (v) {
                            is Number -> trimNum(v.toDouble())
                            is Boolean -> if (v) "是" else "否"
                            is String -> if (v.isNotBlank()) prettyValue(k, v) else null
                            else -> null
                        }
                        if (value != null) rows += label to value
                        if (rows.size >= 9) break
                    }
                }
            }
        }

        // Two live usage endpoints; try both so one stale path never leaves the
        // summary tiles showing "—". Payloads are nested (userQuota/addOnQuota)
        // or flat — collect remaining/total recursively and aggregate them.
        var sumRemain: Double? = null
        var sumTotal: Double? = null
        fun collect(obj: JSONObject) {
            fun numOf(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
                (obj.opt(k) as? Number)?.toDouble()
            }
            numOf("remaining", "remain", "remainingQuota", "quota_remaining")?.let {
                sumRemain = (sumRemain ?: 0.0) + it
            }
            numOf("total", "totalQuota", "quota_total")?.let {
                sumTotal = (sumTotal ?: 0.0) + it
            }
            listOf("userQuota", "addOnQuota", "data", "quota").forEach { k ->
                obj.optJSONObject(k)?.let { collect(it) }
            }
        }
        for (u in listOf("$open/api/v2/quota/usage", "$open/sash/api/v2/me/usage")) {
            runCatching { execute(u, "GET", null, auth) }.getOrNull()?.let { (code, text) ->
                if (code in 200..299) {
                    val d = runCatching { JSONObject(text).optJSONObject("data") ?: JSONObject(text) }.getOrNull()
                    d?.let { collect(it) }
                }
            }
            if (sumRemain != null && sumTotal != null) break
        }
        if (sumTotal != null && sumTotal!! > 0) {
            rows += "剩余额度" to trimNum(sumRemain ?: 0.0)
            rows += "总额度" to trimNum(sumTotal!!)
            val usedPct = ((sumTotal!! - (sumRemain ?: 0.0)) / sumTotal!! * 100).coerceIn(0.0, 100.0)
            rows += "已用占比" to trimNum(usedPct)
        }
        rows.distinctBy { it.first }
    }

    /** Translate known upstream values into readable Chinese. */
    private fun prettyValue(key: String, raw: String): String = when {
        key.equals("userType", true) || key.equals("user_type", true) -> when (raw) {
            "personal_professional_trial" -> "个人专业版（试用）"
            "personal_professional" -> "个人专业版"
            "personal_free", "free" -> "免费版"
            "team", "enterprise" -> "团队/企业版"
            else -> raw.replace("_", " ").replace(Regex("\\b\\w"), { it.value.uppercase() })
        }
        key.equals("plan", true) || key.contains("tier", true) -> when {
            raw.equals("Pro Trial", true) -> "专业试用版（Pro Trial）"
            raw.equals("Pro", true) -> "专业版（Pro）"
            raw.equals("Free", true) -> "免费版（Free）"
            else -> raw
        }
        else -> if (raw.length > 28) raw.take(26) + "…" else raw
    }

    /** Map upstream snake_case / camelCase keys to readable Chinese; null = hide. */
    private fun quotaLabel(key: String): String? = when (key) {
        "plan_tier_name", "plan_name", "name", "plan", "tier" -> null // already added as 套餐
        "is_paid_plan" -> null // already added as 付费类型
        "expiresAt", "expire_at", "expires_at", "end_time" -> null // already added as 到期
        "expire_time" -> "到期"
        "usage_type", "usageType" -> "计费方式"
        "credits" -> "积分类别"
        "total_usage_percentage", "usage_percentage", "totalUsagePercentage" -> "已用占比"
        "remaining", "remain", "quota_remaining" -> "剩余额度"
        "total", "quota_total" -> "总额度"
        "used", "quota_used" -> "已用额度"
        "user_id", "uid", "userId" -> null // uid shown in the account card
        "user_type", "userType" -> "账号类型"
        "machine_id", "device_id" -> null
        else -> if (key.length <= 24 && !key.contains("token", true) &&
            !key.contains("id", true)) key else null
    }

    /** Epoch millis / seconds -> local date; pass through non-numeric values. */
    private fun prettyDate(raw: String): String {
        val v = raw.toDoubleOrNull() ?: return raw
        val ms = if (v > 100_000_000_000.0) v.toLong() else (v * 1000).toLong()
        if (ms <= 0) return raw
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        }.getOrDefault(raw)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.')

    /** Chat endpoint for a region, used by ProviderFactory. */
    fun chatEndpoint(region: String): String = chatBase(region) + "/chat/completions"

    /** Map our stored (upstream) ids back to what the gateway expects. */
    fun upstreamModelId(id: String): String = MODELS_ALIAS[id] ?: id

    // ── COSY chat (agent_chat_generation) ─────────────────────────────────
    // Qoder has NO OpenAI-compatible chat endpoint (POST /model/v1/chat/
    // completions answers 404 — user-observed). Real chat goes through the
    // cosy-signed `agent_chat_generation` gateway with a PRIVATE request body
    // that is cosy-encoded; the SSE response wraps OpenAI-shaped deltas in an
    // envelope. Protocol ported from the community-verified dsh-router-qoder
    // (MIT, live-tested 2026-10) and qoder2api projects. The cosy identity uses
    // the device access token exactly like the already-working model catalogue
    // (cosyHeaders), so no PAT→jobToken exchange is needed here.

    private const val COSY_CUSTOM_ALPHABET =
        "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"
    private const val COSY_STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    /** [FIX-qoder-cosy-pad] standard b64 '=' maps to '$' in the custom alphabet. */
    private const val COSY_CUSTOM_PAD = '$'

    /** cosy custom-base64: standard b64 -> 1/3 block rotation -> alphabet swap. */
    internal fun cosyEncode(plaintext: ByteArray): String {
        val std = Base64.encodeToString(plaintext, Base64.NO_WRAP)
        val n = std.length
        val a = n / 3
        val rearranged = std.substring(n - a) + std.substring(a, n - a) + std.substring(0, a)
        val out = StringBuilder(rearranged.length)
        for (ch in rearranged) {
            val idx = COSY_STD_ALPHABET.indexOf(ch)
            out.append(when {
                ch == '=' -> COSY_CUSTOM_PAD
                idx >= 0 -> COSY_CUSTOM_ALPHABET[idx]
                else -> ch
            })
        }
        return out.toString()
    }

    // ── jobToken exchange (chat identity) ───────────────────────────────
    // The chat gateway (agent_chat_generation) does NOT accept the device
    // access token as the cosy identity — doing so yields HTTP 401
    // {"code":"TOKEN_INVALID","message":"invalid token"}. The official CLI
    // first exchanges a PAT (pt-…) or refresh token (drt-…) for a jobToken
    // via the LEGACY date+signature scheme, then builds the cosy identity from
    // the jobToken's securityOauthToken / id / userType. Ported verbatim from
    // the community-verified dsh-router-qoder (MIT, live-tested 2026-10).

    private const val COSY_APPCODE = "cosy"
    /** base64("war, war never changes") — the legacy signature secret. */
    private const val COSY_SECRET_B64 = "d2FyLCB3YXIgbmV2ZXIgY2hhbmdlcw=="
    private const val CHAT_COSY_VERSION = "0.1.43"

    private data class QoderJobToken(
        val id: String,
        val name: String,
        val securityOauthToken: String,
        val refreshToken: String,
        val userType: String,
        val expireTime: Long,
    )

    private data class CosyFingerprint(
        val machineId: String, val machineType: String, val machineToken: String,
    )

    /** Stable per-credential device fingerprint (gateway treats it as "this machine"). */
    private fun deriveFingerprint(seed: String): CosyFingerprint = CosyFingerprint(
        machineId = md5Hex("machine:$seed"),
        machineType = md5Hex("machinetype:$seed").take(18),
        machineToken = sha512B64Url("machinetoken:$seed").take(43),
    )

    private fun sha512B64Url(s: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-512").digest(s.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    /** Legacy signature used by the jobToken exchange: md5("cosy&<secret>&<date>"). */
    private fun signLegacy(date: String): String =
        md5Hex("$COSY_APPCODE&$COSY_SECRET_B64&$date")

    private fun rfc1123Now(): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    /** Cosy-encoded jobToken exchange body (PAT or drt- refresh token). */
    private fun jobTokenBody(credential: String): String {
        val isRefresh = credential.startsWith("drt-")
        val inner = JSONObject()
            .put("personalToken", if (isRefresh) "" else credential)
            .put("securityOauthToken", "")
            .put("refreshToken", if (isRefresh) credential else "")
            .put("needRefresh", isRefresh)
            .put("authInfo", JSONObject())
        val outer = JSONObject().put("payload", inner.toString()).put("encodeVersion", "1")
        return cosyEncode(outer.toString().toByteArray(Charsets.UTF_8))
    }

    // jobToken is stable for a while — cache per credential to avoid an extra
    // round-trip on every message. Cleared on logout by process restart.
    private val jobTokenCache = java.util.concurrent.ConcurrentHashMap<String, QoderJobToken>()

    /**
     * Exchange the device credential for a jobToken. Prefers the drt- refresh
     * token (long-lived); falls back to the access token (which may be a pt- PAT
     * on some accounts). Throws QoderChatException with the upstream text on
     * failure so the caller surfaces a precise Chinese error.
     */
    /**
     * [FIX-qoder-device-jobtoken] Exchange the DEVICE token (from the PKCE
     * poll) for a job token. Verified against xxhhlk/opencode-qoder-auth
     * (port of CLIProxyAPI), 2026-11:
     *   POST {openapi}/api/v1/me/jobToken
     *   Authorization: Bearer <device token>
     *   body: {"clientId": "<site client id>"}
     * Response carries `token` (the chat token), plus refresh/expiry.
     *
     * This is NOT the gateway's /algo/api/v3/user/jobToken endpoint — that
     * one is PAT-only and returns `personal token is invalid` for a device
     * token. We try both known client ids: the desktop client's (used to log
     * in here) and the CN CLI's (the authoritative CN one). If the exchange
     * is refused (4xx), Qoder CN's CLI simply signs chat with the device
     * token itself, so we fall back to that (deviceChat).
     */
    private fun exchangeJobToken(account: BuiltInAccount): QoderJobToken? {
        val deviceToken = account.accessToken.orEmpty()
        if (deviceToken.isBlank()) return null
        jobTokenCache[deviceToken]?.let { cached ->
            if (cached.expireTime <= 0 || cached.expireTime > System.currentTimeMillis() + 60_000) return cached
        }
        val url = openApiBase(account.region) + "/api/v1/me/jobToken"
        val clientIds = listOf(CLIENT_ID, "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb").distinct()
        for (cid in clientIds) {
            val headers = mapOf(
                "Authorization" to "Bearer $deviceToken",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Accept-Encoding" to "identity",
                "User-Agent" to UA,
                "cosy-clienttype" to "10",
            )
            val (code, text) = execute(url, "POST", JSONObject().put("clientId", cid).toString(), headers)
            if (code != 200) continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val tok = root.optString("token", "")
                .ifEmpty { root.optString("device_token", "") }
                .ifEmpty { root.optString("access_token", "") }
            if (tok.isBlank()) continue
            val jt = QoderJobToken(
                id = "",
                name = "",
                securityOauthToken = tok,
                refreshToken = root.optString("refresh_token", "").ifEmpty { root.optString("refreshToken", "") },
                userType = "",
                expireTime = parseEpoch(root.opt("expire_time") ?: root.opt("expires_at") ?: root.opt("expireTime")),
            )
            jobTokenCache[deviceToken] = jt
            return jt
        }
        // deviceChat fallback: the CN CLI signs chat with the device token.
        return null
    }

    /** Chat signing token: job token when obtainable, else the device token. */
    private fun chatToken(account: BuiltInAccount): String {
        val deviceToken = account.accessToken.orEmpty()
        val jt = runCatching { exchangeJobToken(account) }.getOrNull()
        return jt?.securityOauthToken?.ifBlank { deviceToken } ?: deviceToken
    }

    /** One normalized inbound message for the chat template. */
    internal data class QoderChatMessage(val role: String, val text: String, val images: List<String> = emptyList())

    /** A decoded SSE delta from the chat stream. */
    internal data class QoderChatDelta(
        val role: String = "",
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: JSONArray? = null,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedTokens: Int? = null,
    )

    internal class QoderChatException(message: String, val businessCode: String? = null) : Exception(message)

    private fun chatPath(): String =
        "/algo/api/v2/service/pro/sse/agent_chat_generation?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"

    private fun randId(): String = UUID.randomUUID().toString()

    /**
     * [FIX-qoder-device-jobtoken] Cosy-signed headers for the chat POST.
     * Rebuilt to match the official CLI (xxhhlk/opencode-qoder-auth cosyHeaders
     * + chatHeaders): the SAME 5-field identity and signature scheme that
     * already fetches the 36-model catalogue, with `Cosy-Data-Policy=disagree`
     * and `Cosy-Version=COSY_VERSION`. The signing token is the job token (or
     * the device token on the CN deviceChat fallback), NOT a PAT-derived
     * 9-field identity. The cosy-encoded ciphertext is the signature body.
     */
    private fun chatHeaders(url: String, encodedBody: String, token: String,
                            uid: String, machineId: String, modelKey: String,
                            nickname: String): Map<String, String> {
        val aes = randomAscii(16).toByteArray()
        // 5 fields — identical to the working model-catalogue cosyHeaders.
        val identity = JSONObject()
            .put("uid", uid)
            .put("aid", "")
            .put("name", nickname)
            .put("email", "")
            .put("security_oauth_token", token)
            .toString()
        val aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aesCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(aes))
        val info = b64(aesCipher.doFinal(identity.toByteArray(Charsets.UTF_8)))
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(COSY_PUBLIC_KEY, Base64.DEFAULT)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub)
        val cosyKey = b64(rsa.doFinal(aes))
        val ts = (System.currentTimeMillis() / 1000).toString()
        val payload = JSONObject()
            .put("version", "v1")
            .put("requestId", randId())
            .put("info", info)
            .put("cosyVersion", COSY_VERSION)
            .put("ideVersion", "")
            .toString()
        val payloadB64 = b64(payload.toByteArray(Charsets.UTF_8))
        val path = java.net.URL(url).path.removePrefix("/algo")
        val sig = md5Hex(listOf(payloadB64, cosyKey, ts, encodedBody, path).joinToString("\n"))
        val mid = machineId.ifBlank { randId() }
        return mapOf(
            "Authorization" to "Bearer COSY.$payloadB64.$sig",
            "Cosy-Key" to cosyKey,
            "Cosy-User" to uid,
            "Cosy-Date" to ts,
            "Cosy-Version" to COSY_VERSION,
            "Cosy-Machineid" to mid,
            "Cosy-Machinetoken" to mid,
            "Cosy-Machinetype" to "5",
            "Cosy-Clienttype" to "10",
            "Cosy-Clientip" to mid,
            "Cosy-Scene" to "app",
            "Cosy-Business-Product" to "app",
            "Cosy-Business-Type" to "agent",
            "Cosy-Bodyhash" to md5Hex(encodedBody),
            "Cosy-Bodylength" to encodedBody.length.toString(),
            "Cosy-Sigpath" to path,
            "Cosy-Data-Policy" to "disagree",
            "Cosy-Organization-Id" to "",
            "Cosy-Organization-Tags" to "",
            "Login-Version" to "v2",
            "X-Request-Id" to randId(),
            "X-Model-Key" to modelKey,
            "X-Model-Source" to "system",
            "Cache-Control" to "no-cache",
            "Accept" to "text/event-stream",
            "Accept-Encoding" to "identity",
            "Content-Type" to "application/json",
            "User-Agent" to UA,
        )
    }

    /**
     * [FIX-qoder-device-jobtoken] Qoder's private agent_chat_generation body.
     * Ported from the official CLI (xxhhlk/opencode-qoder-auth qoderBody,
     * 2026-11, verified live on qoder.cn). Key details that differ from the
     * older community template: content is an ARRAY of blocks, there is a
     * top-level `system` plus a system message, `parameters` carries
     * max_tokens, and `business`/`model_config` name the model key (otherwise
     * the call is billed to Auto regardless of the chosen model).
     */
    private fun buildChatBody(modelKey: String, messages: List<QoderChatMessage>, stream: Boolean): String {
        val sysText = "You are a Qoder agent. Use the instructions below and the tools available to you to assist the user."
        fun textBlock(t: String) = JSONObject().put("type", "text").put("text", t)
        val sysBlock = textBlock(sysText)

        fun contentArray(m: QoderChatMessage): JSONArray {
            val arr = JSONArray()
            for (url in m.images) {
                arr.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url)))
            }
            if (m.text.isNotBlank()) arr.put(textBlock(m.text))
            return arr
        }

        val qoderMsgs = JSONArray()
        qoderMsgs.put(JSONObject().put("role", "system").put("content", JSONArray().put(sysBlock)))
        for (m in messages) {
            if (m.role == "system" || m.role == "developer") continue
            if (m.role != "user" && m.role != "assistant") continue
            val content = contentArray(m)
            if (content.length() == 0) continue
            qoderMsgs.put(JSONObject().put("role", m.role).put("content", content))
        }

        val prompt = messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val req = randId()
        val taskId = randId()
        // Stable session per conversation (first user message as seed).
        val seedMsg = messages.firstOrNull { it.role == "user" }?.text.orEmpty()
        val sessionId = "s-" + md5Hex(seedMsg).take(32)
        val maxTokens = 32_000
        val modelConfigObj = JSONObject()
            .put("key", modelKey)
            .put("display_name", modelKey)
            .put("model", "")
            .put("format", "openai")
            .put("is_vl", false)
            .put("is_reasoning", false)
            .put("api_key", "")
            .put("url", "")
            .put("source", "system")
            .put("max_input_tokens", 200_000)
        return JSONObject()
            .put("request_id", req)
            .put("request_set_id", taskId)
            .put("chat_record_id", req)
            .put("session_id", sessionId)
            .put("parameters", JSONObject()
                .put("enable_thinking", false)
                .put("max_tokens", maxTokens))
            .put("business", JSONObject()
                .put("product", "app")
                .put("version", COSY_VERSION)
                .put("type", "agent")
                .put("id", taskId)
                .put("name", prompt.take(30).ifEmpty { "hi" })
                .put("begin_at", System.currentTimeMillis())
                .put("stage", "start")
                .put("model_key", modelKey))
            .put("agent_id", "agent_common")
            .put("task_id", "common")
            .put("session_type", "app")
            .put("model_config", modelConfigObj)
            .put("system", JSONArray().put(sysBlock))
            .put("messages", qoderMsgs)
            .put("stream", stream)
            .toString()
    }

    /** Decode one `data: {...}` SSE payload into a delta. */
    private fun parseChatDelta(payload: String): QoderChatDelta? {
        val wrapper = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val scv = wrapper.optInt("statusCodeValue", 200)
        if (scv != 200) {
            val detail = wrapper.optString("body", payload)
            throw QoderChatException("上游信封错误 HTTP $scv: ${detail.take(200)}")
        }
        val innerStr = wrapper.optString("body", "")
        if (innerStr.isBlank()) return null
        val inner = runCatching { JSONObject(innerStr) }.getOrNull() ?: return null
        val code = inner.optString("code", "")
        if (code.isNotBlank() && code != "0") {
            throw QoderChatException("上游业务错误 code=$code: ${inner.optString("message", "")}", code)
        }
        val usage = inner.optJSONObject("usage")
        val inTok = usage?.optInt("prompt_tokens", 0) ?: 0
        val outTok = usage?.optInt("completion_tokens", 0) ?: 0
        val cached = usage?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", -1)
        val cachedV = if (cached != null && cached >= 0) cached else null
        val choices = inner.optJSONArray("choices") ?: return if (inTok > 0 || outTok > 0)
            QoderChatDelta(inputTokens = inTok, outputTokens = outTok, cachedTokens = cachedV) else null
        for (i in 0 until choices.length()) {
            val d = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
            val role = d.optString("role", "")
            val content = d.optString("content", "")
            val reasoning = d.optString("reasoning_content", "")
            val tools = d.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }
            if (role.isNotBlank() || content.isNotBlank() || reasoning.isNotBlank() || tools != null) {
                return QoderChatDelta(role, content, reasoning, tools, inTok, outTok, cachedV)
            }
        }
        return null
    }

    /**
     * Streaming cosy chat. Yields decoded deltas; the caller (QoderChatProvider)
     * maps them to LLMStreamChunk. Returns a Flow so cancellation propagates.
     */
    internal fun chatStream(account: BuiltInAccount, modelKey: String,
                            messages: List<QoderChatMessage>): Flow<QoderChatDelta> = channelFlow {
        // [FIX-qoder-device-jobtoken] Use the device-token -> jobToken exchange
        // on the openapi host (falls back to the device token itself for CN),
        // then sign with the SAME 5-field cosy identity the model catalogue uses.
        val uid = resolveUid(account)
        if (uid.isBlank()) throw QoderChatException("无法获取账号 uid，无法进行对话")
        val token = withContext(Dispatchers.IO) { chatToken(account) }
        if (token.isBlank()) throw QoderChatException("账号缺少对话凭据，请重新登录 Qoder")
        val mid = account.extra["machineId"].orEmpty().ifBlank { randId() }
        val gateway = gatewayBase(account.region).removeSuffix("/qsvr")
        val url = gateway + chatPath()
        val body = buildChatBody(modelKey, messages, stream = true)
        val encoded = cosyEncode(body.toByteArray(Charsets.UTF_8))
        val headers = chatHeaders(url, encoded, token, uid, mid, modelKey, account.nickname.orEmpty())
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.post(encoded.toRequestBody("application/json".toMediaType()))
        client.newCall(rb.build()).execute().use { resp ->
            if (resp.code != 200) {
                val err = resp.body?.string().orEmpty()
                // business code takes priority over HTTP status
                val code = runCatching { JSONObject(err).optString("code", "") }.getOrNull().orEmpty()
                throw QoderChatException("聊天请求失败 HTTP ${resp.code}: ${err.take(300)}", code.ifBlank { null })
            }
            val reader = resp.body?.byteStream()?.bufferedReader(Charsets.UTF_8)
                ?: throw QoderChatException("聊天响应没有 body")
            reader.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    parseChatDelta(data)?.let { send(it) }
                }
            }
        }
    }

    // ── Daily check-in (campaigns) ────────────────────────────────────────
    // Official CLI 1.1.94: GET {daily}/sash/api/v1/me/campaigns with Bearer +
    // machine identity. Returns { showCampaign, campaigns:[{ campaignId,
    // campaignKey, startAt, endAt, placements:[{ type, content:[lang->{title,
    // detailUrl}], commands:[{name, description, js}] }] }] }.
    // The actual "claim" action is a server-delivered JS snippet that the
    // official client executes in a sandbox WITH a UMID device-risk identity
    // (a native component). We can list the events, but cannot execute that
    // JS/UMID here — so we show the events and explain clearly.
    override val supportsCheckin: Boolean = true

    /**
     * Qoder's "daily check-in" is really the daily CLAIM_BENEFIT campaign.
     * Verified protocol (2026-10):
     *   GET  {openApi}/sash/api/v1/me/campaigns?forceRefresh=true
     *        headers: authorization Bearer <token>, cosy-clienttype: 10,
     *                 accept: application/json, accept-language: zh-CN,
     *                 user-agent: Qoder
     *   POST {openApi}/sash/api/v1/me/campaigns/{campaignId}/claim   body {}
     *        already claimed => HTTP 409 | result=ALREADY_CLAIMED | replayed=true
     * openApi = openapi.qoder.com.cn (CN) / openapi.qoder.sh (INTL).
     * CN needs no UMID device headers; INTL does (see claim note).
     */
    private fun dailyBase(region: String): String = openApiBase(region)

    private fun campaignHeaders(account: BuiltInAccount, post: Boolean = false): Map<String, String> {
        val h = mutableMapOf(
            "authorization" to "Bearer ${account.accessToken}",
            "accept" to "application/json",
            "accept-language" to "zh-CN",
            "cosy-clienttype" to "10",
            "user-agent" to "Qoder",
            "X-Request-Id" to UUID.randomUUID().toString(),
        )
        if (post) {
            h["content-type"] = "application/json"
            h["origin"] = if (isIntl(account.region)) WEBSITE_INTL else WEBSITE_CN
        }
        return h
    }

    private data class QoderCampaign(
        val id: String,
        val claimable: Boolean,
        val claimed: Boolean,
        val points: Int,
    )

    private fun parseCampaignList(text: String): List<QoderCampaign> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("campaigns") ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf<QoderCampaign>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (!c.optString("actionType", "").equals("CLAIM_BENEFIT", true)) continue
            val endAt = c.optLong("endAt", 0) * 1000
            if (endAt in 1 until now) continue
            val id = c.optString("campaignId", "")
            if (id.isBlank()) continue
            val status = c.optString("claimStatus", "")
            val points = c.optJSONObject("benefit")?.optInt("amount", 0) ?: 0
            out += QoderCampaign(
                id = id,
                claimable = status.equals("CLAIMABLE", true),
                claimed = status.equals("CLAIMED", true),
                points = points,
            )
        }
        return out
    }

    private fun buildCheckin(campaigns: List<QoderCampaign>): BuiltInCheckin {
        val benefit = campaigns.firstOrNull { it.claimable || it.claimed }
        val points = benefit?.points ?: 0
        val canClaim = campaigns.any { it.claimable }
        val claimedToday = campaigns.any { it.claimed } && !canClaim
        // Only claim statuses the server actually reported — never fabricate a streak.
        val streak = campaigns.count { it.claimed }.let { if (claimedToday) maxOf(1, it) else it }
        val state = when {
            canClaim -> "claimable"
            claimedToday -> "claimed"
            else -> "upcoming"
        }
        val days = (1..7).map { n ->
            CheckinDay(
                dayNo = n,
                points = if (n == 7) points else 0,
                state = if (n == 7) state else "upcoming",
                isToday = n == 7,
            )
        }
        val message = when {
            canClaim -> "今日有可领取的签到积分（+${points}）"
            claimedToday -> "今日已签到（+${points}）"
            else -> "当前没有可领取的签到活动。官方每天 10:00（北京时间）发放新一轮。"
        }
        return BuiltInCheckin(
            days = days, streak = streak, canClaim = canClaim,
            claimedToday = claimedToday, message = message,
        )
    }

    private fun fetchCampaignsRaw(account: BuiltInAccount): Pair<Int, String> = try {
        execute(
            dailyBase(account.region) + "/sash/api/v1/me/campaigns?forceRefresh=true",
            "GET", null, campaignHeaders(account),
        )
    } catch (e: Exception) {
        -1 to "活动查询失败：${e.message}"
    }

    override suspend fun fetchCheckin(account: BuiltInAccount): BuiltInCheckin = withContext(Dispatchers.IO) {
        val (code, text) = fetchCampaignsRaw(account)
        if (code == -1) return@withContext BuiltInCheckin(error = text)
        if (code == 401 || code == 403) {
            return@withContext BuiltInCheckin(
                error = "签到活动返回 HTTP $code" + upstreamSnippet(text) +
                    if (isIntl(account.region)) "\n国际版签到需要官方客户端的设备风控身份（UMID），本应用无法生成。" else "",
            )
        }
        if (code !in 200..299) {
            return@withContext BuiltInCheckin(error = "签到活动返回 HTTP $code" + upstreamSnippet(text))
        }
        buildCheckin(parseCampaignList(text))
    }

    override suspend fun claimCheckin(account: BuiltInAccount): BuiltInCheckin = withContext(Dispatchers.IO) {
        val (code, text) = fetchCampaignsRaw(account)
        if (code == -1) return@withContext BuiltInCheckin(error = text)
        if (code !in 200..299) {
            return@withContext BuiltInCheckin(error = "签到活动返回 HTTP $code" + upstreamSnippet(text))
        }
        val campaigns = parseCampaignList(text)
        val claimable = campaigns.filter { it.claimable }
        if (claimable.isEmpty()) {
            return@withContext buildCheckin(campaigns).copy(
                message = if (campaigns.any { it.claimed }) "今日已签到，无需重复领取"
                          else "当前没有可领取的签到活动（官方每天 10:00 发放新一轮）",
            )
        }
        var gained = 0
        var already = false
        var lastErr: String? = null
        for (c in claimable) {
            val (cc, ct) = try {
                execute(
                    dailyBase(account.region) + "/sash/api/v1/me/campaigns/${c.id}/claim",
                    "POST", "{}", campaignHeaders(account, post = true),
                )
            } catch (e: Exception) {
                lastErr = e.message
                continue
            }
            val replayed = runCatching { JSONObject(ct) }.getOrNull()?.let { o ->
                o.optBoolean("replayed", false) ||
                    o.optString("result", "") == "ALREADY_CLAIMED" ||
                    o.optString("status", "") == "CLAIMED" ||
                    (o.optJSONObject("data")?.optString("status", "") == "CLAIMED")
            } ?: false
            when {
                cc == 409 || replayed -> already = true
                cc in 200..299 -> gained += c.points
                else -> lastErr = "HTTP $cc" + upstreamSnippet(ct)
            }
        }
        val base = buildCheckin(campaigns)
        base.copy(
            streak = maxOf(1, base.streak),
            canClaim = false, claimedToday = true,
            message = when {
                gained > 0 -> "签到成功，+${gained} 积分"
                already -> "今日已签到（活动已领取）"
                else -> "签到未完成：${lastErr ?: "未知原因"}"
            },
        )
    }

    internal fun errorOf(e: Exception): IOException = IOException(e.message ?: "Qoder 请求失败")
}
