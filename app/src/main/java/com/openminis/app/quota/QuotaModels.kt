package com.openminis.app.quota

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal



@Suppress("EnumEntryName")
@Serializable
enum class QuotaVendor {
    deepSeek, moonshot, siliconFlow, openRouter,
    codexSubscription, openAIPlatform,
    claudeSubscription, anthropicPlatform,
    geminiAPIKey, geminiCodeAssist, antigravity,
    xAI, kimiCode, githubCopilot,

    xiaomiMiMo,

    miniMax,

    openCode,
    customRelay, unknown,
}

@Serializable
data class QuotaSubjectID(val vendor: QuotaVendor, val accountKey: String) {
    override fun toString(): String = "${vendor.name}:$accountKey"
}

@Serializable(with = QuotaPoolKeySerializer::class)
data class QuotaPoolKey(val rawValue: String) : Comparable<QuotaPoolKey> {
    override fun toString(): String = rawValue
    override fun compareTo(other: QuotaPoolKey): Int = rawValue.compareTo(other.rawValue)

    companion object {
        fun of(subject: QuotaSubjectID, family: String, qualifier: String? = null): QuotaPoolKey {
            var s = "${subject.vendor.name}:${subject.accountKey}:$family"
            if (!qualifier.isNullOrEmpty()) s += ":$qualifier"
            return QuotaPoolKey(s)
        }
    }
}

object QuotaPoolKeySerializer : KSerializer<QuotaPoolKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaPoolKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: QuotaPoolKey) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): QuotaPoolKey = QuotaPoolKey(decoder.decodeString())
}


@Serializable
data class QuotaSubject(
    val id: QuotaSubjectID,
    val kind: Kind = Kind.credential,
    val parent: QuotaSubjectID? = null,
    val instanceIds: Set<String> = emptySet(),
    val accountLabel: String? = null,
    val plan: QuotaPlan? = null,
    val state: State = State.Available,
    val lastRefresh: QuotaRefreshRecord? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind { account, credential, organization, project }

    @Serializable
    sealed class State {
        @Serializable @SerialName("unsupported")
        data class Unsupported(val reason: UnsupportedReason) : State()

        @Serializable @SerialName("awaitingFirstObservation")
        data object AwaitingFirstObservation : State()

        @Serializable @SerialName("notConfigured")
        data class NotConfigured(val reason: NotConfiguredReason) : State()

        @Serializable @SerialName("available")
        data object Available : State()

        @Serializable @SerialName("failed")
        data object Failed : State()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class UnsupportedReason {
        vendorHasNoQuotaAPI,

        unknownVendor,

        providerTypeUnsupported,

        passiveNotImplemented,

        capabilityProbeFailed,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class NotConfiguredReason { missingCredential, oauthNotSupported, instanceDisabled, relayProbeOff }
}

@Serializable
data class QuotaPlan(val rawId: String, val tierName: String? = null)


@Serializable
data class UpstreamQuotaSnapshot(
    val subjectId: QuotaSubjectID,
    val provenance: QuotaProvenance,
    val capturedAt: Long,
    val serverClockOffset: Long? = null,
    val plan: QuotaPlan? = null,
    val pools: List<QuotaPool>,
    val bindings: List<QuotaPoolBinding>,
    val accountAvailable: Boolean? = null,
)

@Serializable
data class QuotaSnapshotSummary(
    val subjectId: QuotaSubjectID,
    val source: QuotaSource,
    val adapterId: String,
    val capturedAt: Long,
    val poolKeys: List<QuotaPoolKey>,
)

@Serializable
data class QuotaProvenance(
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val adapterId: String,
    val endpoint: String? = null,
    val relayHost: String? = null,
)

@Suppress("EnumEntryName")
@Serializable
enum class QuotaSource {
    officialQuery,

    officialHeaders,

    officialStreamEvent,

    officialErrorSignal,

    providerDashboardAPI,

    compatibleInterface,

    relayAccountLedger,

    derived,
}

@Suppress("EnumEntryName")
@Serializable
enum class QuotaConfidence { documented, observedClient, unverified, thirdParty, userDeclared }


@Serializable
data class QuotaPool(
    val id: QuotaPoolKey,
    val subjectId: QuotaSubjectID,
    val kind: Kind,
    val unit: QuotaUnit,
    val vendorLabel: String? = null,
    val amounts: QuotaAmounts? = null,
    val windows: List<QuotaWindow> = emptyList(),
    val verdict: Verdict? = null,
    val unlimited: Boolean = false,
    val provenance: QuotaProvenance,
    val observedAt: Long,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        prepaidBalance,

        spendCap,

        allowance,

        usageWindow,

        rateLimit,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Verdict { allowed, warning, rejected }
}

@Serializable
sealed class QuotaUnit {
    @Serializable @SerialName("currency")
    data class Currency(val code: String) : QuotaUnit()

    @Serializable @SerialName("credits")
    data class Credits(val name: String? = null) : QuotaUnit()

    @Serializable @SerialName("requests")
    data object Requests : QuotaUnit()

    @Serializable @SerialName("tokens")
    data class Tokens(val kind: TokenKind) : QuotaUnit()

    @Serializable @SerialName("percentOnly")
    data object PercentOnly : QuotaUnit()

    @Serializable @SerialName("rateLimit")
    data class RateLimit(val metric: RateMetric) : QuotaUnit()

    @Serializable @SerialName("custom")
    data class Custom(val name: String) : QuotaUnit()

    @Suppress("EnumEntryName")
    @Serializable
    enum class TokenKind { input, output, total }

    @Suppress("EnumEntryName")
    @Serializable
    enum class RateMetric { requests, tokens, inputTokens, outputTokens, concurrency }
}

@Serializable
data class QuotaAmounts(
    @Serializable(with = QuotaDecimalSerializer::class) val total: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val used: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val remaining: BigDecimal? = null,
    val breakdown: List<Part> = emptyList(),
) {
    @Serializable
    data class Part(
        val role: Role,
        @Serializable(with = QuotaDecimalSerializer::class) val amount: BigDecimal,
        val expiresAt: Long? = null,
    )

    @Serializable
    sealed class Role {
        @Serializable @SerialName("granted") data object Granted : Role()
        @Serializable @SerialName("toppedUp") data object ToppedUp : Role()
        @Serializable @SerialName("voucher") data object Voucher : Role()
        @Serializable @SerialName("cash") data object Cash : Role()
        @Serializable @SerialName("other") data class Other(val name: String) : Role()
    }
}

@Serializable
data class QuotaWindow(
    val id: String,
    val durationSeconds: Long? = null,
    val calendarPeriod: CalendarPeriod? = null,
    val isRolling: Boolean,
    val amounts: QuotaAmounts? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val reportedUsedFraction: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val reportedRemainingFraction: BigDecimal? = null,
    val resetsAt: Long? = null,
    val observedAt: Long,
    val provenance: QuotaProvenance,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class CalendarPeriod { day, week, month }
}


@Serializable
data class QuotaPoolBinding(
    val poolKey: QuotaPoolKey,
    val target: Target,
    val evidence: Evidence,
) {
    @Serializable
    sealed class Target {
        @Serializable @SerialName("subject") data object Subject : Target()

        @Serializable @SerialName("model") data class Model(val key: String) : Target()

        @Serializable @SerialName("modelFamily") data class ModelFamily(val pattern: String) : Target()

        @Serializable @SerialName("product") data class Product(val name: String) : Target()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Evidence {
        explicitUpstream,

        vendorDocumented,

        assumedSubjectWide,

        userManualMapping;

        val rank: Int
            get() = when (this) {
                explicitUpstream -> 3
                vendorDocumented -> 2
                assumedSubjectWide -> 1
                userManualMapping -> 0
            }
    }
}


@Serializable
data class QuotaCapabilityKey(
    val providerType: String,
    val baseURL: String,
) {
    override fun toString(): String = "$providerType@$baseURL"

    companion object {
        fun normalize(raw: String?): String {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty()) return "default"
            s = s.lowercase()
            s = s.trimEnd('/')
            if (s.endsWith("/v1")) s = s.dropLast(3)
            return s.trimEnd('/')
        }
    }
}

data class QuotaCapability(
    val active: Boolean,
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val manualRefresh: Boolean,
    val unsupportedReason: QuotaSubject.UnsupportedReason?,
) {
    companion object {
        fun unsupported(reason: QuotaSubject.UnsupportedReason) = QuotaCapability(
            active = false, source = QuotaSource.officialQuery, confidence = QuotaConfidence.documented,
            manualRefresh = false, unsupportedReason = reason,
        )

        fun activeQuery(
            source: QuotaSource = QuotaSource.officialQuery,
            confidence: QuotaConfidence = QuotaConfidence.documented,
        ) = QuotaCapability(
            active = true, source = source, confidence = confidence,
            manualRefresh = true, unsupportedReason = null,
        )
    }
}

@Serializable
data class QuotaCapabilityRecord(
    val key: QuotaCapabilityKey,
    val adapterId: String,
    val status: Status,
    val source: QuotaSource? = null,
    val confidence: QuotaConfidence? = null,
    val probedAt: Long,
    val detail: String? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Status {
        supported,

        unsupported,

        failed,
    }
}


@Serializable
data class QuotaRefreshRecord(
    val attemptedAt: Long,
    val succeededAt: Long? = null,
    val error: QuotaError? = null,
    val nextAllowedAt: Long? = null,
    val serverCooldownUntil: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastForcedAt: Long? = null,
)

@Serializable
data class QuotaError(
    val kind: Kind,
    val detail: String,
    val httpStatus: Int? = null,
    val at: Long,
    val retryAfter: Long? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        network, unauthorized, forbidden, notFound, rateLimited, server,
        badResponse, unsupportedResponse, missingCredential,
    }
}


data class QuotaUtilization(
    val percent: BigDecimal,
    val basis: Basis,
) {
    @Suppress("EnumEntryName")
    enum class Basis { reported, reportedComplement, computed }
}

@Suppress("EnumEntryName")
enum class QuotaStatus {
    unknown, available, low, rateLimited, exhausted;

    val severity: Int get() = ordinal

    val isWarning: Boolean get() = this == low || this == rateLimited || this == exhausted
}

@Suppress("EnumEntryName")
enum class QuotaFreshness { fresh, stale, expired }

data class PoolStatus(
    val status: QuotaStatus,
    val decidingWindowId: String?,
    val utilization: QuotaUtilization?,
    val freshness: QuotaFreshness,
)

data class ModelQuotaView(
    val instanceId: String,
    val modelKey: String,
    val subjectId: QuotaSubjectID?,
    val bound: List<BoundPool>,
    val effective: QuotaStatus,
    val deciding: BoundPool?,
    val isIncomplete: Boolean,
    val hasModelSpecificPool: Boolean,
) {
    data class BoundPool(
        val poolKey: QuotaPoolKey,
        val binding: QuotaPoolBinding,
        val status: PoolStatus,
    ) {
        val isShared: Boolean get() = binding.target == QuotaPoolBinding.Target.Subject
        val isManual: Boolean get() = binding.evidence == QuotaPoolBinding.Evidence.userManualMapping
    }

    companion object {
        fun empty(instanceId: String, modelKey: String) = ModelQuotaView(
            instanceId = instanceId, modelKey = modelKey, subjectId = null, bound = emptyList(),
            effective = QuotaStatus.unknown, deciding = null, isIncomplete = false, hasModelSpecificPool = false,
        )
    }
}

data class QuotaThresholds(
    val lowPercent: BigDecimal = BigDecimal(80),
    val exhaustedPercent: BigDecimal = BigDecimal(100),
    val lowBalance: Map<QuotaSubjectID, Map<String, BigDecimal>> = emptyMap(),
)


object QuotaDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.quotaString)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString()
        return QuotaDecimalParser.parseString(raw) ?: throw SerializationException("not a decimal: $raw")
    }
}

fun BigDecimal.canonical(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

val BigDecimal.quotaString: String
    get() = if (signum() == 0) "0" else stripTrailingZeros().toPlainString()

object QuotaDecimalParser {
    private val allowed = Regex("^[0-9.+\\-eE]+$")

    fun parse(value: JsonElement?): BigDecimal? {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        if (!value.isString && value.booleanOrNull != null) return null
        return parseString(value.content)
    }

    fun parseString(raw: String?): BigDecimal? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!allowed.matches(s) || s.none { it.isDigit() }) return null
        return try {
            BigDecimal(s).canonical()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
