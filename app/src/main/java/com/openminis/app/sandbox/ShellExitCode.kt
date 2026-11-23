package com.openminis.app.sandbox

internal object ShellExitCode {

    fun suffix(code: Int): String = "(exit code: $code)"

    fun ensureSuffix(output: String, code: Int): String =
        if (output.trimEnd().endsWith(suffix(code))) output else "$output\n${suffix(code)}"
}
