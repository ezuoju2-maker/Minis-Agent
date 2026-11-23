package com.openminis.app.config.audit

import com.openminis.app.config.readable.ReadableSummary

enum class ConfigAuditActor(val raw: String) {
    AGENT("agent"),
    USER("user"),
    AGENT_REVERT("agent-revert"),
    USER_REVERT("user-revert");

    companion object {
        fun fromRaw(s: String?): ConfigAuditActor =
            values().firstOrNull { it.raw == s } ?: AGENT
    }
}

enum class ConfigAuditStatus(val raw: String) {
    APPLIED("applied"),
    REJECTED("rejected"),
    TIMEOUT("timeout"),
    REVERTED("reverted");

    companion object {
        fun fromRaw(s: String?): ConfigAuditStatus? =
            values().firstOrNull { it.raw == s }
    }
}

data class ConfigAuditEntry(
    val id: String,
    val at: Long,
    val actor: ConfigAuditActor,
    val sessionId: String?,
    val scope: String,
    val key: String,
    val oldValueJSON: String,
    val newValueJSON: String,
    val confirmedAt: Long?,
    val status: ConfigAuditStatus,
    val revertOf: String?,
    val caption: String?,
    val readable: ReadableSummary? = null,
)
