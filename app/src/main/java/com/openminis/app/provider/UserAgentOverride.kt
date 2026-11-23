package com.openminis.app.provider

import com.openminis.app.BuildConfig
import okhttp3.Request

fun Request.Builder.applyUserAgentOverride(
    customUserAgent: String?,
    defaultUserAgent: String? = MinisUserAgent.DEFAULT,
): Request.Builder {
    val ua = customUserAgent?.trim()
    when {
        !ua.isNullOrEmpty() -> header("User-Agent", ua)
        defaultUserAgent != null -> header("User-Agent", defaultUserAgent)
    }
    return this
}

object MinisUserAgent {
    val DEFAULT: String by lazy {
        val release = android.os.Build.VERSION.RELEASE ?: "unknown"
        val model = (android.os.Build.MODEL ?: "unknown").trim().ifEmpty { "unknown" }
        "Minis/${BuildConfig.VERSION_NAME} (Android $release; $model)"
    }
}
