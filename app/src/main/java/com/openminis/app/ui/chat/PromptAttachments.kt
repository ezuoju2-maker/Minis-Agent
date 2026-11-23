package com.openminis.app.ui.chat

internal object PromptAttachments {
    fun <T> of(ownsComposer: Boolean, own: List<T>, composer: List<T>): List<T> = if (ownsComposer) composer else own

    fun clearsComposer(ownsComposer: Boolean): Boolean = ownsComposer
}
