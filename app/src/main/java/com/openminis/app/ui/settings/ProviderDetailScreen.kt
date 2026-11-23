package com.openminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.util.bringIntoViewOnFocus
import com.openminis.app.R
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField

private const val TAG = "ProviderDetail"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderDetailScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
    onAddCustomModel: () -> Unit = {},
    onVoiceServiceClick: (String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<com.openminis.app.data.model.ModelEntry?>(null) }
    var menuEntryId by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (instance == null && !deleted) { onBack(); return }
    if (instance == null) return

    var label by remember { mutableStateOf(instance.label) }
    val labelChanged = label != instance.label

    val detailContext = androidx.compose.ui.platform.LocalContext.current
    val oauthConnected = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
        com.openminis.app.auth.OAuthManager.forInstance(detailContext, instance)
            ?.isAuthenticated() == true
    var storedKey by remember(instanceId) {
        mutableStateOf(
            providerRepository.loadApiKey(instanceId)
                ?: if (oauthConnected) OAUTH_CONNECTED_PLACEHOLDER else "",
        )
    }
    var isEditingKey by remember { mutableStateOf(false) }
    var editKeyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var isEnabled by remember { mutableStateOf(instance.isEnabled) }
    var customBaseURL by remember { mutableStateOf(instance.customBaseURL ?: "") }
    var appendV1Suffix by remember { mutableStateOf(instance.appendV1Suffix) }
    var customUserAgent by remember { mutableStateOf(instance.customUserAgent ?: "") }

    val entries = providerRepository.entriesFor(instanceId)
    val (visibleEntries, hiddenEntries) = entries.partition { !it.isHidden }
    var hiddenExpanded by remember(instanceId) { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshAuthError by remember { mutableStateOf(false) }
    var refreshOutcome by remember { mutableStateOf<com.openminis.app.data.repository.ProviderRepository.ModelRefreshOutcome?>(null) }

    val exportContext = androidx.compose.ui.platform.LocalContext.current

    SettingsScaffold(
        title = instance.label,
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                AppLogger.info(TAG, "Export instance ${instance.id} (${instance.label})")
                exportProviderInstance(exportContext, providerRepository, instance)
            }) {
                Icon(Icons.Default.IosShare, contentDescription = stringResource(R.string.provider_detail_export))
            }
        },
    ) {
        SettingsSection(header = stringResource(R.string.provider_detail_label)) {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fieldModifier = Modifier.bringIntoViewOnFocus(),
                    )
                    if (labelChanged) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MinisSmallButton(onClick = {
                            providerRepository.updateInstance(instance.copy(label = label))
                            AppLogger.info(TAG, "Updated label for ${instance.id}: '$label'")
                        }) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }

        val isOAuthProvider =
            instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth
        SettingsSection(
            header = if (isOAuthProvider) stringResource(R.string.add_provider_credential) else stringResource(R.string.provider_list_api_key),
            footer = if (isOAuthProvider) {
                "OAuth tokens are stored securely in encrypted storage."
            } else {
                null
            },
        ) {
            SettingsCardBlock {
                if (isOAuthProvider) {
                    OAuthCredentialBlock(
                        instance = instance,
                        storedKey = storedKey,
                        providerRepository = providerRepository,
                    )
                } else {
                    ApiKeyCredentialBlock(
                        storedKey = storedKey,
                        keyVisible = keyVisible,
                        onToggleVisibility = { keyVisible = !keyVisible },
                        isEditing = isEditingKey,
                        editValue = editKeyValue,
                        onEditValueChange = { editKeyValue = it },
                        onBeginEdit = {
                            isEditingKey = true
                            editKeyValue = storedKey
                        },
                        onCancelEdit = {
                            isEditingKey = false
                            editKeyValue = ""
                            keyVisible = false
                        },
                        onSave = {
                            providerRepository.saveApiKey(instanceId, editKeyValue)
                            storedKey = editKeyValue
                            AppLogger.info(TAG, "Saved API key for ${instance.id}")
                            isEditingKey = false
                            editKeyValue = ""
                            keyVisible = false
                        },
                    )
                }
            }
        }

        if (isOAuthProvider && instance.providerType != ProviderType.githubCopilot) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_manual_bearer_token),
                footer = stringResource(R.string.provider_detail_use_a_static_bearer_token_instead_of_the) +
                    "Useful with custom proxy endpoints.",
            ) {
                SettingsCardBlock {
                    ManualBearerTokenSection(
                        instance = instance,
                        context = androidx.compose.ui.platform.LocalContext.current,
                    )
                }
            }
        }

        if (instance.providerType != ProviderType.openRouter) {
            SettingsSection(header = stringResource(R.string.provider_detail_custom_api_base)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SectionTextField(
                        value = customBaseURL,
                        onValueChange = { customBaseURL = it },
                        singleLine = true,
                        placeholder = stringResource(R.string.provider_detail_https_api_example_placeholder),
                        fieldModifier = Modifier.bringIntoViewOnFocus(),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.provider_detail_auto_append_v1),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsSwitch(
                        checked = appendV1Suffix,
                        onCheckedChange = { appendV1Suffix = it },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                val showUserAgentField = instance.providerType == ProviderType.openAI ||
                    instance.providerType == ProviderType.anthropic
                if (showUserAgentField) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.provider_detail_custom_user_agent),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        SectionTextField(
                            value = customUserAgent,
                            onValueChange = { customUserAgent = it },
                            singleLine = true,
                            placeholder = stringResource(R.string.provider_detail_custom_user_agent_placeholder),
                            fieldModifier = Modifier.bringIntoViewOnFocus(),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                MinisSmallTextButton(
                    onClick = {
                        providerRepository.updateInstance(
                            instance.copy(
                                customBaseURL = customBaseURL.ifBlank { null },
                                appendV1Suffix = appendV1Suffix,
                                customUserAgent = customUserAgent.ifBlank { null },
                            )
                        )
                        AppLogger.info(
                            TAG,
                            "Saved base URL for ${instance.id}: " +
                                "url='${customBaseURL.ifBlank { "<default>" }}', appendV1=$appendV1Suffix, " +
                                "ua='${customUserAgent.ifBlank { "<default>" }}'",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.provider_detail_save_url_settings))
                }
            }
        }

        ProviderNetworkRouteSection(instance.id)

        if (instance.providerType == ProviderType.openAI &&
            instance.credentialType != com.openminis.app.data.model.ProviderCredential.oauth
        ) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_api_format),
                footer = if (instance.useResponsesAPI) {
                    stringResource(R.string.provider_detail_api_format_footer_responses)
                } else {
                    stringResource(R.string.provider_detail_api_format_footer_chat)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !instance.useResponsesAPI,
                            onClick = {
                                if (instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = false))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                        SegmentedButton(
                            selected = instance.useResponsesAPI,
                            onClick = {
                                if (!instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = true))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                    }
                }
            }
        }

        if (instance.providerType == ProviderType.openAI &&
            instance.credentialType != com.openminis.app.data.model.ProviderCredential.oauth
        ) {
            ResponseIdleTimeoutSection(instance.id)
        }

        if (instance.supportsAzureMode) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_azure_openai),
                footer = stringResource(R.string.provider_detail_azure_openai_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.provider_detail_azure_openai),
                    checked = instance.azureMode,
                    onCheckedChange = { on ->
                        providerRepository.updateInstance(instance.copy(azureMode = on))
                        AppLogger.info(TAG, "Set azureMode=$on for ${instance.id}")
                    },
                    showDivider = false,
                )
            }
        }


        if (instance.supportsImageEndpointSetting) {
            val mode = instance.imageEndpointMode
            SettingsSection(
                header = "Image Generation",
                footer = when (mode) {
                    com.openminis.app.data.model.ImageEndpointMode.auto ->
                        if (instance.imageEndpointResolved != null) {
                            val resolved = if (instance.imageEndpointResolved ==
                                com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
                            ) "/v1/images/generations" else "/v1/chat/completions"
                            "Auto: tries /v1/images/generations first, falls back to /v1/chat/completions. Last successful endpoint: $resolved."
                        } else {
                            "Auto: tries /v1/images/generations first, falls back to /v1/chat/completions. Caches the working endpoint after the first call."
                        }
                    com.openminis.app.data.model.ImageEndpointMode.imagesGenerations ->
                        "Always use /v1/images/generations."
                    com.openminis.app.data.model.ImageEndpointMode.chatCompletions ->
                        "Always use /v1/chat/completions (multimodal output)."
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.auto,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.auto) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.auto,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        ) { Text("Auto") }
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.imagesGenerations) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
                                            imageEndpointResolved = null,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        ) { Text("Images API") }
                        SegmentedButton(
                            selected = mode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                            onClick = {
                                if (mode != com.openminis.app.data.model.ImageEndpointMode.chatCompletions) {
                                    providerRepository.updateInstance(
                                        instance.copy(
                                            imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
                                            imageEndpointResolved = null,
                                        ),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        ) { Text("Chat") }
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.provider_detail_status)) {
            SettingsSwitchRow(
                title = stringResource(R.string.provider_detail_enabled),
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    providerRepository.updateInstance(instance.copy(isEnabled = it))
                    AppLogger.info(TAG, "Set enabled=$it for ${instance.id}")
                },
                showDivider = false,
            )
        }

        com.openminis.app.ui.quota.ProviderQuotaSection(instance = instance)

        if (providerRepository.hasVoiceModels(instance.id)) {
            SettingsSection(header = null) {
                SettingsRow(
                    title = stringResource(R.string.voice_services_section),
                    onClick = { onVoiceServiceClick(instance.id) },
                    showDivider = false,
                    trailing = if (providerRepository.isVoiceShadowDisabled(instance.id)) {
                        {
                            Text(
                                text = stringResource(R.string.voice_service_hidden_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        ThinkingRulesSection(instance = instance, providerRepository = providerRepository)

        SettingsSection(
            header = stringResource(R.string.provider_detail_models_count_header, entries.size),
        ) {
            SettingsRow(
                title = stringResource(if (isRefreshing) R.string.provider_refreshing_models else R.string.provider_refresh_model_list),
                subtitle = refreshOutcome?.let { o ->
                    val error = o.vendorError
                    when {
                        error == null -> stringResource(R.string.provider_refresh_result_ok, o.total, o.added)
                        o.source == com.openminis.app.data.repository.ProviderRepository.ModelRefreshSource.KEPT && o.added > 0 ->
                            stringResource(R.string.provider_refresh_result_builtin, error, o.added)
                        o.source == com.openminis.app.data.repository.ProviderRepository.ModelRefreshSource.KEPT ->
                            stringResource(R.string.provider_refresh_result_kept, error)
                        else -> stringResource(R.string.provider_refresh_result_fallback, error, o.total)
                    }
                },
                onClick = if (isRefreshing) {
                    null
                } else {
                    {
                        isRefreshing = true
                        refreshAuthError = false
                        refreshOutcome = null
                        scope.launch {
                            try {
                                refreshOutcome = providerRepository.refreshModels(instance, forceRefresh = true)
                                AppLogger.info(TAG, "Refreshed models for ${instance.id}")
                            } catch (e: com.openminis.app.data.repository.CodexDiscoveryAuthException) {
                                AppLogger.warning(TAG, "Refresh rejected credential: ${e.message}")
                                refreshAuthError = true
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                },
                showChevron = false,
                trailing = {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.provider_detail_refresh_models),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                showDivider = entries.isNotEmpty() || true,
            )

            val modelRow: @Composable (com.openminis.app.data.model.ModelEntry, Boolean) -> Unit = { entry, showRowDivider ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier)
                        .combinedClickable(
                            onClick = { onModelEntryClick(entry.id) },
                            onLongClick = { menuEntryId = entry.id },
                        ),
                ) {
                    val inputModalities = entry.model.inputModalities.orEmpty()
                    val outputModalities = entry.model.outputModalities.orEmpty()
                    val hasBadge = inputModalities.any { it in modalityIconKeys } ||
                        outputModalities.any { it in modalityOutputIconKeys }
                    SettingsRow(
                        title = entry.model.displayName,
                        subtitle = entry.model.id,
                        onClick = null,
                        showChevron = true,
                        showDivider = showRowDivider,
                        minHeight = 72.dp,
                        trailing = {
                            Box(
                                modifier = Modifier.width(72.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                if (hasBadge) {
                                    ModalityIconsRow(inputModalities, outputModalities)
                                }
                            }
                        },
                    )

                    DropdownMenu(
                        expanded = menuEntryId == entry.id,
                        onDismissRequest = { menuEntryId = null },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (entry.isHidden) R.string.provider_detail_show_model
                                        else R.string.provider_detail_hide_model,
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (entry.isHidden) Icons.Filled.Visibility
                                    else Icons.Filled.VisibilityOff,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuEntryId = null
                                providerRepository.updateEntry(entry.copy(isHidden = !entry.isHidden))
                                AppLogger.info(
                                    TAG,
                                    "Model entry ${entry.id} (${entry.model.displayName}) " +
                                        "isHidden ${entry.isHidden} -> ${!entry.isHidden}",
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuEntryId = null
                                entryToDelete = entry
                            },
                        )
                    }
                }
            }

            visibleEntries.forEachIndexed { idx, entry ->
                key(entry.id) { modelRow(entry, idx != visibleEntries.lastIndex || hiddenEntries.isNotEmpty()) }
            }

            if (hiddenEntries.isNotEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.provider_detail_hidden_models_group, hiddenEntries.size),
                    onClick = { hiddenExpanded = !hiddenExpanded },
                    showChevron = false,
                    showDivider = hiddenExpanded,
                    trailing = {
                        Icon(
                            if (hiddenExpanded) Icons.Default.ExpandMore
                            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (hiddenExpanded) {
                    hiddenEntries.forEachIndexed { idx, entry ->
                        key(entry.id) { modelRow(entry, idx != hiddenEntries.lastIndex) }
                    }
                }
            }
        }

        MinisOutlinedButton(
            onClick = onAddCustomModel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.provider_detail_add_custom_model))
        }

        MinisButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_delete_provider))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (refreshAuthError) {
        MinisAlertDialog(
            onDismissRequest = { refreshAuthError = false },
            title = stringResource(R.string.provider_refresh_auth_failed_title),
            text = stringResource(R.string.provider_refresh_auth_failed_body),
            confirmText = stringResource(R.string.ok),
            dismissText = stringResource(R.string.common_close),
            onConfirm = { refreshAuthError = false },
        )
    }

    if (showDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.provider_detail_delete_provider),
            text = stringResource(R.string.provider_detail_delete_provider_confirm, instance.label),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                deleted = true
                providerRepository.removeInstance(instanceId)
                AppLogger.info(TAG, "Deleted provider instance ${instance.id} (${instance.label})")
                showDeleteDialog = false
                onBack()
            },
        )
    }

    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(
                if (e.isCustom) R.string.provider_detail_delete_model_confirm
                else R.string.provider_detail_delete_api_model_confirm,
                e.model.displayName,
            ),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                AppLogger.info(TAG, "Deleted model entry ${e.id} (${e.model.displayName})")
                entryToDelete = null
            },
        )
    }
}


private const val OAUTH_CONNECTED_PLACEHOLDER = "oauth"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OAuthCredentialBlock(
    instance: com.openminis.app.data.model.ProviderInstance,
    storedKey: String,
    providerRepository: ProviderRepository,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var displayedKey by remember(instance.id) { mutableStateOf(storedKey) }
    var isAuthenticating by remember(instance.id) { mutableStateOf(false) }
    var authError by remember(instance.id) { mutableStateOf<String?>(null) }
    val oauthPhase by com.openminis.app.auth.OAuthForegroundGate.phase.collectAsState()
    var kimiDeviceAuth by remember(instance.id) {
        mutableStateOf<com.openminis.app.auth.KimiDeviceFlow.DeviceAuthorization?>(null)
    }
    var kimiLoginJob by remember(instance.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    kimiDeviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.openUrl,
            onCancel = {
                kimiLoginJob?.cancel()
                kimiDeviceAuth = null
            },
        )
    }

    var needsReauth by remember(instance.id) {
        mutableStateOf(com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id))
    }

    fun startSignIn() {
        if (isAuthenticating) return
        isAuthenticating = true
        authError = null
        com.openminis.app.auth.OAuthForegroundGate.begin()
        kimiLoginJob = scope.launch {
            try {
                val token = when (instance.providerType) {
                    ProviderType.kimiCode ->
                        com.openminis.app.auth.KimiOAuthManager.login(
                            context, instance.id, providerRepository,
                            onDeviceCode = { auth -> kimiDeviceAuth = auth },
                        ).also { kimiDeviceAuth = null }
                    ProviderType.anthropic ->
                        com.openminis.app.auth.ClaudeOAuthManager.login(
                            context, instance.id, providerRepository,
                        )
                    ProviderType.openAI ->
                        com.openminis.app.auth.OpenAIOAuthManager.login(
                            context, instance.id, providerRepository,
                        )
                    ProviderType.openRouter ->
                        com.openminis.app.auth.OpenRouterOAuthManager.login(
                            context, instance.id, providerRepository,
                        )
                    ProviderType.xAI ->
                        com.openminis.app.auth.XAIOAuthManager.login(
                            context, instance.id, providerRepository,
                        )
                    else -> null
                }
                if (token != null) {
                    displayedKey = providerRepository.loadApiKey(instance.id) ?: token
                    needsReauth = false
                    AppLogger.info(TAG, "OAuth signed in for ${instance.id}")
                    providerRepository.triggerAsyncModelReconcile(instance.id, forceRefresh = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.warning(TAG, "OAuth sign-in failed for ${instance.id}: ${e.message}")
                authError = if (e is com.openminis.app.auth.OAuthNetworkUnreachableException) {
                    context.getString(R.string.add_provider_oauth_network_unreachable)
                } else {
                    context.getString(
                        R.string.oauth_sign_in_failed,
                        e.message ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                isAuthenticating = false
                kimiDeviceAuth = null
                com.openminis.app.auth.OAuthForegroundGate.end()
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "OAuth",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (displayedKey.isNotEmpty()) {
            Text(
                maskedKey(displayedKey),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(
                        if (needsReauth) MaterialTheme.colorScheme.error else Color(0xFF34C759),
                        CircleShape,
                    ),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                if (isAuthenticating) stringResource(R.string.oauth_status_signing_in) else "Not connected",
                color = if (isAuthenticating) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    val signOutButton: @Composable () -> Unit = {
        MinisSmallButton(
            onClick = {
                com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.logout()
                providerRepository.deleteApiKey(instance.id)
                displayedKey = ""
                needsReauth = false
                AppLogger.info(TAG, "OAuth signed out for ${instance.id} (tokens + apiKey cleared)")
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_sign_out))
        }
    }
    if (displayedKey.isNotEmpty() && needsReauth) {
        Text(
            stringResource(R.string.provider_oauth_sign_in_expired_detail),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisSmallButton(
                enabled = !isAuthenticating,
                onClick = { startSignIn() },
            ) {
                Text(stringResource(R.string.provider_oauth_sign_in_again))
            }
            signOutButton()
        }
        OAuthProgressLine(isAuthenticating = isAuthenticating, phase = oauthPhase, error = authError)
    } else if (displayedKey.isNotEmpty()) {
        signOutButton()
    } else {
        MinisSmallButton(
            enabled = !isAuthenticating,
            onClick = { startSignIn() },
        ) {
            Text(stringResource(R.string.provider_detail_sign_in))
        }
        OAuthProgressLine(isAuthenticating = isAuthenticating, phase = oauthPhase, error = authError)
    }
}

@Composable
private fun OAuthProgressLine(
    isAuthenticating: Boolean,
    phase: com.openminis.app.auth.OAuthForegroundGate.Phase?,
    error: String?,
) {
    if (isAuthenticating) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    if (phase == com.openminis.app.auth.OAuthForegroundGate.Phase.COMPLETING) {
                        R.string.oauth_completing_sign_in
                    } else {
                        R.string.oauth_waiting_for_authorization
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ApiKeyCredentialBlock(
    storedKey: String,
    keyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
) {
    if (isEditing) {
        SectionTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide" else "Show",
                    )
                }
            },
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            MinisSmallOutlinedButton(
                onClick = onCancelEdit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallButton(onClick = onSave, enabled = editValue.isNotBlank()) {
                Text(stringResource(R.string.provider_detail_save_key))
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (keyVisible) storedKey else maskedKey(storedKey),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (keyVisible) "Hide" else "Show",
                )
            }
            MinisSmallTextButton(onClick = onBeginEdit) {
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

private fun maskedKey(key: String): String {
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}

private fun exportProviderInstance(
    context: android.content.Context,
    providerRepository: ProviderRepository,
    instance: com.openminis.app.data.model.ProviderInstance,
) {
    val json = providerRepository.exportInstanceJSON(instance.id) ?: return
    val safeLabel = instance.label.ifBlank { "provider" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeLabel.json")
    runCatching { file.writeText(json) }.onFailure { return }

    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (_: Throwable) {
        return
    }

    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeLabel.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export provider").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

@Composable
private fun ManualBearerTokenSection(
    instance: com.openminis.app.data.model.ProviderInstance,
    context: android.content.Context,
) {
    val manager = remember(instance.id) {
        com.openminis.app.auth.OAuthManager.forInstance(context, instance)
    }
    var reloadTick by remember(instance.id) { mutableStateOf(0) }
    val stored = remember(instance.id, reloadTick) { manager?.loadManualBearerToken() }
    val hasToken = !stored.isNullOrEmpty()

    var isEditing by remember(instance.id) { mutableStateOf(false) }
    var draft by remember(instance.id) { mutableStateOf("") }

    if (isEditing) {
        SectionTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            placeholder = stringResource(R.string.provider_detail_paste_bearer_token),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            MinisSmallOutlinedButton(
                onClick = {
                    isEditing = false
                    draft = ""
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallButton(
                onClick = {
                    val cleaned = draft.replace(Regex("\\s+"), "")
                    if (cleaned.isNotEmpty() && manager != null) {
                        manager.saveManualBearerToken(cleaned)
                        AppLogger.info(TAG, "Manual bearer token set for ${instance.id}")
                        draft = ""
                        isEditing = false
                        reloadTick++
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.provider_detail_save_token))
            }
        }
        return
    }

    if (hasToken) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Configured",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(Color(0xFF34C759), CircleShape),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            MinisSmallOutlinedButton(onClick = {
                draft = ""
                isEditing = true
            }) {
                Text(stringResource(R.string.common_change))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallOutlinedButton(
                onClick = {
                    manager?.deleteManualBearerToken()
                    AppLogger.info(TAG, "Manual bearer token removed for ${instance.id}")
                    reloadTick++
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.common_remove))
            }
        }
    } else {
        MinisSmallOutlinedButton(onClick = {
            draft = ""
            isEditing = true
        }) {
            Text(stringResource(R.string.provider_detail_set_manual_bearer_token))
        }
    }
}

private val modalityIconKeys = setOf("image", "pdf", "audio", "video")

private val modalityOutputIconKeys = setOf("image", "audio", "video")

@Composable
private fun ModalityIconsRow(
    inputModalities: List<String>,
    outputModalities: List<String>,
) {
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val outputTint = MaterialTheme.colorScheme.primary
    val size = Modifier.size(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if ("image" in inputModalities) Icon(Icons.Default.Image, contentDescription = stringResource(R.string.modeldetail_image_input), tint = inputTint, modifier = size)
        if ("pdf" in inputModalities) Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = stringResource(R.string.modeldetail_pdf_input), tint = inputTint, modifier = size)
        if ("audio" in inputModalities) Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.modeldetail_audio_input), tint = inputTint, modifier = size)
        if ("video" in inputModalities) Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.modeldetail_video_input), tint = inputTint, modifier = size)
        if ("image" in outputModalities) Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.modeldetail_image_output), tint = outputTint, modifier = size)
        if ("audio" in outputModalities) Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.modeldetail_audio_output), tint = outputTint, modifier = size)
        if ("video" in outputModalities) Icon(Icons.Default.MovieCreation, contentDescription = stringResource(R.string.modeldetail_video_output), tint = outputTint, modifier = size)
    }
}

@Composable
private fun ResponseIdleTimeoutSection(instanceId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val setting = com.openminis.app.provider.ResponseIdleTimeoutSetting
    var seconds by remember(instanceId) { mutableStateOf(setting.seconds(context, instanceId)) }
    var menuOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    fun label(s: Int): String = when (s) {
        120 -> context.getString(R.string.response_idle_timeout_2min_default)
        300 -> context.getString(R.string.response_idle_timeout_5min)
        600 -> context.getString(R.string.response_idle_timeout_10min)
        1800 -> context.getString(R.string.response_idle_timeout_30min)
        3600 -> context.getString(R.string.response_idle_timeout_60min)
        else -> context.getString(R.string.response_idle_timeout_seconds, s)
    }
    fun apply(s: Int) {
        setting.set(context, instanceId, s)
        seconds = setting.seconds(context, instanceId)
        AppLogger.info(TAG, "Set responseIdleTimeout=${seconds}s for $instanceId")
    }
    SettingsSection(
        header = stringResource(R.string.provider_detail_response_idle_timeout),
        footer = stringResource(R.string.provider_detail_response_idle_timeout_footer),
    ) {
        Box {
            SettingsRow(
                title = stringResource(R.string.provider_detail_response_idle_timeout),
                onClick = { menuOpen = true },
                showChevron = false,
                showDivider = false,
                trailing = { Text(label(seconds), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (preset in setting.PRESETS) {
                    DropdownMenuItem(
                        text = { Text(label(preset)) },
                        onClick = { menuOpen = false; apply(preset) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.response_idle_timeout_custom)) },
                    onClick = { menuOpen = false; customOpen = true },
                )
            }
        }
    }
    if (customOpen) {
        var text by remember { mutableStateOf(seconds.toString()) }
        val parsed = text.trim().toIntOrNull()
        val valid = parsed != null && parsed in setting.MIN_SECONDS..setting.MAX_SECONDS
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text(stringResource(R.string.provider_detail_response_idle_timeout)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    isError = !valid,
                    label = { Text(stringResource(R.string.response_idle_timeout_seconds_field)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = valid,
                    onClick = { customOpen = false; parsed?.let { apply(it) } },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { customOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
