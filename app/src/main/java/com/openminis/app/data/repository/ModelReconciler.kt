package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ModelReconciler(
    private val scope: CoroutineScope,
    private val lookup: (String) -> ProviderInstance?,
    private val refresh: suspend (ProviderInstance, Boolean) -> Unit,
) {
    fun trigger(instanceId: String, forceRefresh: Boolean): Job = scope.launch {
        val instance = lookup(instanceId)
        if (instance == null) {
            Log.i(TAG, "[ModelReconcile] $instanceId no longer exists; skipped")
            return@launch
        }
        try {
            refresh(instance, forceRefresh)
            Log.i(TAG, "[ModelReconcile] refreshed ${instance.id} (${instance.providerType}) force=$forceRefresh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "[ModelReconcile] refresh failed for ${instance.id}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ProviderRepo"
    }
}
