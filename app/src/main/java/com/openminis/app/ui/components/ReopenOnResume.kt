package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

@Composable
fun rememberReopenOnResume(onReopen: () -> Unit): () -> Unit {
    var pending by rememberSaveable { mutableStateOf(false) }
    val latestOnReopen by rememberUpdatedState(onReopen)
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pending) {
                pending = false
                scope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    latestOnReopen()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember { { pending = true } }
}
