package com.openminis.app.tools

data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val toolTitle: String = "",
    val pageURL: String? = null,
    val imageFilePath: String? = null,
    val imageLinuxPath: String? = null,
    val timedOut: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolExecutionResult) return false
        return output == other.output && success == other.success &&
            imageData.contentEquals(other.imageData) &&
            imageMimeType == other.imageMimeType && toolTitle == other.toolTitle &&
            pageURL == other.pageURL && imageFilePath == other.imageFilePath &&
            imageLinuxPath == other.imageLinuxPath &&
            timedOut == other.timedOut
    }

    override fun hashCode(): Int {
        var result = output.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageMimeType?.hashCode() ?: 0)
        result = 31 * result + toolTitle.hashCode()
        result = 31 * result + (pageURL?.hashCode() ?: 0)
        result = 31 * result + (imageFilePath?.hashCode() ?: 0)
        result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
        result = 31 * result + timedOut.hashCode()
        return result
    }
}
