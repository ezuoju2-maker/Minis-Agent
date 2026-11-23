package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.effectiveInputModalities
import com.openminis.app.data.model.effectiveOutputModalities
import com.openminis.app.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
    val instance = config.instances.find { it.id == instanceId }

    if (entry == null) {
        onBack()
        return
    }

    val baseModel = entry.baseModel
    val overrides = entry.overrides

    var modelId by remember { mutableStateOf(baseModel.id) }
    var displayName by remember { mutableStateOf(overrides.displayName ?: baseModel.displayName) }
    var displayNameTouched by remember { mutableStateOf(false) }
    var maxOutputTokensText by remember { mutableStateOf(overrides.maxOutputTokens?.toString() ?: "") }
    var contextWindowText by remember { mutableStateOf(overrides.contextWindow?.toString() ?: "") }
    var thinkingEnabled by remember {
        mutableStateOf(overrides.supportsReasoning ?: baseModel.supportsReasoning ?: false)
    }
    var thinkingTouched by remember { mutableStateOf(false) }
    var isHidden by remember { mutableStateOf(entry.isHidden) }
    var showQuickTest by remember { mutableStateOf(false) }

    val effectiveInput = (
        overrides.inputModalities
            ?: baseModel.effectiveInputModalities
            ?: emptyList()
        ).map { it.normalizeModalityName() }
    val effectiveOutput = (
        overrides.outputModalities
            ?: baseModel.effectiveOutputModalities
            ?: emptyList()
        ).map { it.normalizeModalityName() }
    var imageInput by remember { mutableStateOf("image" in effectiveInput) }
    var pdfInput by remember { mutableStateOf("pdf" in effectiveInput) }
    var audioInput by remember { mutableStateOf("audio" in effectiveInput) }
    var videoInput by remember { mutableStateOf("video" in effectiveInput) }
    var imageOutput by remember { mutableStateOf("image" in effectiveOutput) }
    var audioOutput by remember { mutableStateOf("audio" in effectiveOutput) }
    var inputModalitiesTouched by remember { mutableStateOf(false) }
    var outputModalitiesTouched by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        onBack = null,
        centerTitle = true,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    val newInputs = buildList {
                        if (imageInput) add("image")
                        if (pdfInput) add("pdf")
                        if (audioInput) add("audio")
                        if (videoInput) add("video")
                    }
                    val newOutputs = buildList {
                        if (imageOutput) add("image")
                        if (audioOutput) add("audio")
                    }
                    val newOverrides = buildModelOverrides(
                        existing = overrides,
                        displayNameText = displayName,
                        displayNameTouched = displayNameTouched,
                        maxOutputTokensText = maxOutputTokensText,
                        contextWindowText = contextWindowText,
                        thinkingEnabled = thinkingEnabled,
                        thinkingTouched = thinkingTouched,
                        inputModalities = newInputs,
                        inputModalitiesTouched = inputModalitiesTouched,
                        outputModalities = newOutputs,
                        outputModalitiesTouched = outputModalitiesTouched,
                    )
                    val updated = if (entry.isCustom) {
                        entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides, isHidden = isHidden)
                    } else {
                        entry.copy(overrides = newOverrides, isHidden = isHidden)
                    }
                    providerRepository.updateEntry(updated)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = if (entry.isCustom) stringResource(R.string.modeldetail_identity_footer_custom)
                     else stringResource(R.string.modeldetail_identity_footer_builtin),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    onValueChange = { if (entry.isCustom) modelId = it },
                    singleLine = true,
                    readOnly = !entry.isCustom,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_display_name))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it; displayNameTouched = true },
                    singleLine = true,
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.model_entry_provider),
                value = instance?.label ?: instanceId,
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
            footer = stringResource(R.string.modeldetail_capabilities_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_context_window))
                SectionTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.contextWindow?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
                SectionTextField(
                    value = maxOutputTokensText,
                    onValueChange = { maxOutputTokensText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.maxOutputTokens?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                val enteredTokens = maxOutputTokensText.trim().toIntOrNull()
                val baseLimit = baseModel.maxOutputTokens
                if (enteredTokens != null && baseLimit != null && enteredTokens > baseLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it; thinkingTouched = true },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.model_entry_visibility),
            footer = stringResource(R.string.model_entry_hidden_models_won_t_appear_in_the_model_),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.model_entry_hidden),
                checked = isHidden,
                onCheckedChange = { isHidden = it },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.modeldetail_section_input_modality),
            footer = stringResource(R.string.modeldetail_input_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = imageInput,
                onCheckedChange = { imageInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_pdf_input),
                checked = pdfInput,
                onCheckedChange = { pdfInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_input),
                checked = audioInput,
                onCheckedChange = { audioInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_input),
                checked = videoInput,
                onCheckedChange = { videoInput = it; inputModalitiesTouched = true },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.modeldetail_section_output_modality),
            footer = stringResource(R.string.modeldetail_output_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_output),
                checked = imageOutput,
                onCheckedChange = { imageOutput = it; outputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_output),
                checked = audioOutput,
                onCheckedChange = { audioOutput = it; outputModalitiesTouched = true },
                showDivider = false,
            )
        }

        SettingsSection(
            footer = stringResource(R.string.quicktest_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = Icons.Outlined.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }

        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
                footer = stringResource(R.string.model_entry_restores_the_provider_reported_display_n),
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        providerRepository.updateEntry(entry.copy(overrides = ModelOverrides(), isHidden = false))
                        onBack()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}

internal fun buildModelOverrides(
    existing: ModelOverrides,
    displayNameText: String,
    displayNameTouched: Boolean,
    maxOutputTokensText: String,
    contextWindowText: String,
    thinkingEnabled: Boolean,
    thinkingTouched: Boolean,
    inputModalities: List<String>,
    inputModalitiesTouched: Boolean,
    outputModalities: List<String>,
    outputModalitiesTouched: Boolean,
): ModelOverrides = ModelOverrides(
    displayName = if (displayNameTouched) {
        displayNameText.trim().takeIf { it.isNotEmpty() }
    } else {
        existing.displayName
    },
    maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
    contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 },
    supportsReasoning = if (thinkingTouched) thinkingEnabled else existing.supportsReasoning,
    inputModalities = if (inputModalitiesTouched) inputModalities else existing.inputModalities,
    outputModalities = if (outputModalitiesTouched) outputModalities else existing.outputModalities,
    maxThinkingLevel = existing.maxThinkingLevel,
    temperature = existing.temperature,
    topP = existing.topP,
    customHeaders = existing.customHeaders,
    extraBodyParams = existing.extraBodyParams,
)
