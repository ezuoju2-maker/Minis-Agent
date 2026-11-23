package com.openminis.app.ui.chat

data class DocOrder(val row: Int, val sub: Int) : Comparable<DocOrder> {
    override fun compareTo(other: DocOrder): Int =
        compareValuesBy(this, other, DocOrder::row, DocOrder::sub)
}

class ShardText(
    val id: TextShardId,
    val plainText: String,
    val rawMarkdown: String?,
    val y: () -> Float?,
)

internal fun chatRowKeyForShard(id: TextShardId): String? {
    val base = shardBaseId(id.shardId)
    return when {
        base.startsWith("text:") -> "text:${id.messageId}:${base.removePrefix("text:")}"
        base.startsWith("mdblock:") -> "mdblock:${id.messageId}:${base.removePrefix("mdblock:")}"
        base == "legacy" -> "legacy:${id.messageId}"
        else -> null
    }
}

internal fun shardSubIndex(shardId: String): Int {
    val hash = shardId.lastIndexOf('#')
    if (hash < 0) return 0
    return shardId.substring(hash + 1).toIntOrNull() ?: 0
}

private fun shardBaseId(shardId: String): String {
    val hash = shardId.lastIndexOf('#')
    return if (hash >= 0 && shardId.substring(hash + 1).toIntOrNull() != null) shardId.substring(0, hash) else shardId
}

internal fun chatDocumentOrder(rowIndexByKey: () -> Map<String, Int>): (TextShardId) -> DocOrder? = { id ->
    chatRowKeyForShard(id)
        ?.let { rowIndexByKey()[it] }
        ?.let { DocOrder(it, shardSubIndex(id.shardId)) }
}
