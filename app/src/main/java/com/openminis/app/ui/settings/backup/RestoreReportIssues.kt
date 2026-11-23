package com.openminis.app.ui.settings.backup

import com.openminis.app.backup.BackupImporter

internal enum class RestoreIssueSeverity {
    ERROR,

    NOTICE,
}

internal enum class RestoreIssueKind(val severity: RestoreIssueSeverity) {
    FAILED(RestoreIssueSeverity.ERROR),

    MISSING_FROM_PACKAGE(RestoreIssueSeverity.ERROR),

    UNREADABLE(RestoreIssueSeverity.ERROR),

    SIZE_SKIPPED(RestoreIssueSeverity.NOTICE),

    NOT_DOWNLOADED(RestoreIssueSeverity.NOTICE),
}

internal data class RestoreIssue(
    val kind: RestoreIssueKind,
    val categoryKey: String,
    val count: Int,
    val detail: String? = null,
) {
    val severity: RestoreIssueSeverity get() = kind.severity
}

internal fun restoreIssues(report: BackupImporter.Report): List<RestoreIssue> = buildList {
    for (c in report.categories) {
        c.failed?.let { add(RestoreIssue(RestoreIssueKind.FAILED, c.category, 1, it)) }
        if (c.missingBlobs > 0) {
            add(RestoreIssue(RestoreIssueKind.MISSING_FROM_PACKAGE, c.category, c.missingBlobs))
        }
        if (c.unreadable > 0) {
            add(RestoreIssue(RestoreIssueKind.UNREADABLE, c.category, c.unreadable))
        }
        if (c.sizeSkippedInPackage > 0) {
            add(RestoreIssue(RestoreIssueKind.SIZE_SKIPPED, c.category, c.sizeSkippedInPackage))
        }
        if (c.notDownloadedInPackage > 0) {
            add(RestoreIssue(RestoreIssueKind.NOT_DOWNLOADED, c.category, c.notDownloadedInPackage))
        }
    }
}

internal enum class RestoreCredentialsNotice { RESTORED, KEPT, NONE_IN_PACKAGE }

internal fun restoreCredentialsNotice(providers: BackupImporter.CategoryReport): RestoreCredentialsNotice? = when {
    providers.credentialsRestored > 0 -> RestoreCredentialsNotice.RESTORED
    providers.credentialsKept > 0 -> RestoreCredentialsNotice.KEPT
    providers.imported + providers.updated > 0 -> RestoreCredentialsNotice.NONE_IN_PACKAGE
    else -> null
}
