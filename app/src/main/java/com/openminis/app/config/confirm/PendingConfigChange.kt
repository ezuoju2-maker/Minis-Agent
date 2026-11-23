package com.openminis.app.config.confirm

import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.readable.ReadableSummary
import java.util.UUID

data class PendingConfigChangeItem(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val path: String,
    val oldDisplay: String,
    val newDisplay: String,
    val verb: String,
    val risk: ConfigRisk,
    val readable: ReadableSummary? = null,
    val isApproved: Boolean = true,
)

data class PendingConfigChange(
    val id: String = UUID.randomUUID().toString(),
    val items: List<PendingConfigChangeItem>,
    val caption: String?,
)

sealed class ConfirmOutcome {
    data class Approved(val items: List<PendingConfigChangeItem>) : ConfirmOutcome()
    object Rejected : ConfirmOutcome()
    object TimedOut : ConfirmOutcome()
}
