package com.openminis.app.backup.remote

object RcloneBackendCatalog {

    data class Field(
        val key: String,
        val label: String,
        val placeholder: String,
        val isSecret: Boolean = false,
        val isOptional: Boolean = false,
        val keyboard: KeyboardKind = KeyboardKind.DEFAULT,
    )

    enum class KeyboardKind { DEFAULT, URL, NUMERIC, EMAIL }

    data class Backend(
        val type: String,
        val title: String,
        val subtitle: String,
        val fields: List<Field>,
    )

    fun usesAbsolutePaths(backendType: String): Boolean = backendType == "sftp"

    val all: List<Backend> = listOf(
        Backend(
            type = "smb",
            title = "SMB / Windows Share",
            subtitle = "NAS, Windows shared folder, Samba",
            fields = listOf(
                Field("host", "Server", "192.168.1.10"),
                Field("user", "Username", "admin", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("domain", "Domain", "WORKGROUP", isOptional = true),
            ),
        ),
        Backend(
            type = "webdav",
            title = "WebDAV",
            subtitle = "Nextcloud, ownCloud, Synology, alist",
            fields = listOf(
                Field("url", "URL", "https://example.com/dav", keyboard = KeyboardKind.URL),
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
            ),
        ),
        Backend(
            type = "sftp",
            title = "SFTP",
            subtitle = "SSH file transfer",
            fields = listOf(
                Field("host", "Server", "example.com"),
                Field("user", "Username", ""),
                Field("pass", "Password", "", isSecret = true),
                Field("port", "Port", "22", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
        Backend(
            type = "s3",
            title = "S3-Compatible Storage",
            subtitle = "MinIO, Cloudflare R2, Wasabi, Alibaba OSS, Tencent COS…",
            fields = listOf(
                Field("endpoint", "Endpoint", "s3.example.com", keyboard = KeyboardKind.URL),
                Field("access_key_id", "Access Key ID", ""),
                Field("secret_access_key", "Secret Access Key", "", isSecret = true),
                Field("region", "Region", "us-east-1", isOptional = true),
            ),
        ),
        Backend(
            type = "ftp",
            title = "FTP",
            subtitle = "Plain or explicit TLS",
            fields = listOf(
                Field("host", "Server", "ftp.example.com"),
                Field("user", "Username", "", isOptional = true),
                Field("pass", "Password", "", isSecret = true, isOptional = true),
                Field("port", "Port", "21", isOptional = true, keyboard = KeyboardKind.NUMERIC),
            ),
        ),
    )

    fun backend(type: String): Backend? = all.firstOrNull { it.type == type }

    fun secretField(type: String): String? =
        backend(type)?.fields?.firstOrNull { it.isSecret }?.key
}
