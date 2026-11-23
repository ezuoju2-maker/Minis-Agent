package com.openminis.app.data.session

import android.content.Context
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SessionDeleter {
    private const val TAG = "SessionDeleter"

    data class Report(val deletedIds: List<String>, val bytesFreed: Long, val jobsCancelled: Int)

    suspend fun deleteTree(
        context: Context,
        repo: ChatRepository,
        rootId: String,
        reason: String,
    ): Report = withContext(Dispatchers.IO) {
        val storage = SessionStorage(context.applicationContext.filesDir)
        val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
        var freed = 0L
        var jobs = 0
        for (id in ids) {
            AgentJobRegistry.jobForSession(id)?.let {
                if (it.isActive) {
                    AgentJobRegistry.setThen(it.id, com.openminis.app.agent.jobs.AgentJobThen.None)
                    AgentJobRegistry.cancel(it.id, "session-deleted:$reason"); jobs++
                }
            }
            for (child in AgentJobRegistry.activeChildren(id)) {
                AgentJobRegistry.setThen(child.id, com.openminis.app.agent.jobs.AgentJobThen.None)
            }
            AgentJobRegistry.dropQueuedDelegations(id, "parent-deleted:$reason")
            AgentJobRegistry.cancelAll(id, "parent-deleted:$reason")
            runCatching {
                com.openminis.app.scheduled.ScheduledTaskManager(context.applicationContext)
                    .disableAllCreatedBy(id, "session-deleted:$reason")
            }
            runCatching { HeadlessChatRunner.cancel(context, id) }
            withContext(Dispatchers.Main) { ChatViewModelStore.release(id) }
            HeadlessChatRunner.forget(id)
            repo.deleteSessionOnly(id)
            freed += storage.deleteFiles(id)
            SessionBadgeStore.clear(id)
            com.openminis.app.sandbox.offload.BrowserCliPools.release(id)
        }
        AppLogger.info(
            TAG,
            "deleteTree root=${rootId.take(8)} reason=$reason sessions=${ids.size} " +
                "jobsCancelled=$jobs freed=${freed}B",
        )
        Report(ids, freed, jobs)
    }

    suspend fun clearTreeFiles(context: Context, repo: ChatRepository, rootId: String): Long =
        withContext(Dispatchers.IO) {
            val storage = SessionStorage(context.applicationContext.filesDir)
            val ids = SessionTree.subtreeDeepestFirst(rootId) { repo.dao.childSessionIds(it) }
            ids.sumOf { storage.deleteFiles(it) }
        }
}
