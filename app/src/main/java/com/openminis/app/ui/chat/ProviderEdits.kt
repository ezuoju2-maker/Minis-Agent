package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderInstance

internal fun editedProviderInstanceIds(
    before: Map<String, ProviderInstance>,
    now: Map<String, ProviderInstance>,
): Set<String> = now.filter { (id, inst) -> before[id] != null && before[id] != inst }.keys
