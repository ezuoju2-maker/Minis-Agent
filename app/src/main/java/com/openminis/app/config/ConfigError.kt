package com.openminis.app.config

sealed class ConfigError(message: String) : Exception(message) {
    class TypeMismatch(expected: String) :
        ConfigError("type_mismatch: expected $expected")

    class InvalidValue(msg: String) :
        ConfigError("invalid_value: $msg")

    class OutOfRange(min: Double?, max: Double?) :
        ConfigError(
            "out_of_range: " + listOfNotNull(
                min?.let { "min=$it" },
                max?.let { "max=$it" }
            ).joinToString(", ")
        )

    class RegexMismatch(pattern: String) :
        ConfigError("regex_mismatch: must match /$pattern/")

    class PermissionDenied(reason: String = "Hidden from minis-config") :
        ConfigError("permission_denied: $reason")

    class UnknownPath(path: String) :
        ConfigError("unknown_path: $path")

    class AlreadyExists(reason: String) :
        ConfigError("already_exists: $reason")

    class IoError(msg: String) :
        ConfigError("io_error: $msg")
}
