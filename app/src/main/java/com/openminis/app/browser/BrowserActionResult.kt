package com.openminis.app.browser

data class BrowserActionResult(
    val text: String,
    val success: Boolean = true,
    val base64Image: String? = null,
    val imageFilePath: String? = null,
    val fetchedFileData: ByteArray? = null,
    val fetchedFileName: String? = null,
    val pageURL: String? = null,
    val tabId: Int? = null,
) {
    companion object {
        fun error(message: String) = BrowserActionResult(
            text = "Error: $message",
            success = false,
        )
    }
}
