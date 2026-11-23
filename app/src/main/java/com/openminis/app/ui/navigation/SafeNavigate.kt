package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

fun NavController.safeNavigate(
    route: String,
    builder: (NavOptionsBuilder.() -> Unit)? = null,
) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    if (builder != null) navigate(route, builder) else navigate(route)
}

fun NavController.safePopBackStack(route: String, inclusive: Boolean = false): Boolean {
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack(route, inclusive)
}

fun NavController.safePopBackStack(): Boolean {
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack()
}
