package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.SessionSearch

internal object SearchFolders {

    fun matchedFolderIds(folders: List<FolderEntity>, query: String): Set<String> {
        val terms = SessionSearch.searchTerms(query)
        if (terms.isEmpty()) return emptySet()
        return folders.filter { SessionSearch.firstHit(it.name, terms) != null }.map { it.id }.toSet()
    }

    fun withFolderMembers(
        results: List<ChatSessionEntity>,
        all: List<ChatSessionEntity>,
        matchedFolderIds: Set<String>,
    ): List<ChatSessionEntity> {
        if (matchedFolderIds.isEmpty()) return results
        val ids = results.mapTo(HashSet()) { it.id }
        val extra = all.filter { s -> s.folderId != null && s.folderId in matchedFolderIds && s.id !in ids }
        if (extra.isEmpty()) return results
        return (results + extra).sortedByDescending { it.updatedAt }
    }
}
