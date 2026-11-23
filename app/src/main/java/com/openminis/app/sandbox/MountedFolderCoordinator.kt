package com.openminis.app.sandbox

import com.openminis.app.data.MountedFoldersStore

object MountedFolderCoordinator {

    private const val MOUNTS_PREFIX = "/var/minis/mounts/"

    fun requireWritable(linuxPath: String, store: MountedFoldersStore) {
        if (isLinuxPathUnderReadOnlyMount(linuxPath, store)) {
            throw ReadOnlyMountException(linuxPath)
        }
    }

    fun isLinuxPathUnderReadOnlyMount(linuxPath: String, store: MountedFoldersStore): Boolean {
        if (!linuxPath.startsWith(MOUNTS_PREFIX)) return false
        val tail = linuxPath.substring(MOUNTS_PREFIX.length)
        val name = tail.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    fun bindMountSpecs(store: MountedFoldersStore): List<Pair<String, String>> =
        store.entries.value
            .mapNotNull { e ->
                val host = e.resolvedHostPath ?: return@mapNotNull null
                "$MOUNTS_PREFIX${e.name}" to host
            }
}

class ReadOnlyMountException(val linuxPath: String) : Exception(
    "$linuxPath is inside a read-only mounted folder and cannot be modified. " +
        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
)
