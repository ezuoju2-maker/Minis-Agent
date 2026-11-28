package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.auth.OpenRouterOAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import kotlinx.coroutines.launch
import java.util.UUID
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField

private enum class AddProviderStep {
    CHOOSE_TYPE,
    CHOOSE_CREDENTIAL,
    CONFIGURE,
}

@Composable
fun AddProviderScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    initialType: ProviderType? = null,
) {
    var step by remember {
        mutableStateOf(
            when {
                initialType == null -> AddProviderStep.CHOOSE_TYPE
                availableCredentials(initialType).size == 1 -> AddProviderStep.CONFIGURE
                else -> AddProviderStep.CHOOSE_CREDENTIAL
            }
        )
    }
    var selectedType by remember { mutableStateOf(initialType) }
    var selectedCredential by remember {
        mutableStateOf(
            if (initialType != null && availableCredentials(initialType).size == 1) {
                availableCredentials(initialType).first()
            } else null
        )
    }
    // [T-android-provider-voice] Non-null when the flow was entered from a
    var selectedVoiceTemplate by remember {
        mutableStateOf<com.openminis.app.data.model.VoiceProviderTemplate?>(null)
    }

    val handleBack: () -> Unit = {
        when (step) {
            AddProviderStep.CHOOSE_TYPE -> onBack()
            AddProviderStep.CHOOSE_CREDENTIAL -> {
                step = AddProviderStep.CHOOSE_TYPE
                selectedType = null
            }
            AddProviderStep.CONFIGURE -> {
                if (selectedVoiceTemplate != null) {
                    step = AddProviderStep.CHOOSE_TYPE
                    selectedType = null
                    selectedVoiceTemplate = null
                } else {
                    val creds = availableCredentials(selectedType!!)
                    if (creds.size == 1) {
                        step = AddProviderStep.CHOOSE_TYPE
                        selectedType = null
                    } else {
                        step = AddProviderStep.CHOOSE_CREDENTIAL
                    }
                }
                selectedCredential = null
            }
        }
    }

    BackHandler(enabled = step != AddProviderStep.CHOOSE_TYPE) { handleBack() }

    when (step) {
        AddProviderStep.CHOOSE_TYPE -> ChooseProviderScreen(
            onBack = handleBack,
            onSelect = { type ->
                selectedType = type
                val creds = availableCredentials(type)
                if (creds.size == 1) {
                    selectedCredential = creds.first()
                    step = AddProviderStep.CONFIGURE
                } else {
                    step = AddProviderStep.CHOOSE_CREDENTIAL
                }
            },
            onSelectVoiceTemplate = { template ->
                selectedVoiceTemplate = template
                selectedType = template.providerType
                selectedCredential = ProviderCredential.apiKey
                step = AddProviderStep.CONFIGURE
            },
        )
        AddProviderStep.CHOOSE_CREDENTIAL -> ChooseCredentialScreen(
            providerType = selectedType!!,
            onBack = handleBack,
            onSelect = { credential ->
                selectedCredential = credential
                step = AddProviderStep.CONFIGURE
            },
        )
        AddProviderStep.CONFIGURE -> ConfigureProviderScreen(
            providerType = selectedType!!,
            credentialType = selectedCredential!!,
            providerRepository = providerRepository,
            voiceTemplate = selectedVoiceTemplate,
            onBack = handleBack,
            onSaved = onSaved,
        )
    }
}

private val providerDisplayOrder = listOf(
    ProviderType.openAI,
    ProviderType.openAIResponses,
    ProviderType.anthropic,
    ProviderType.gemini,
    ProviderType.xAI,
    ProviderType.kimiCode,
    ProviderType.minimaxCode,
    ProviderType.qoder,
    ProviderType.workBuddy,
    ProviderType.openRouter,
    ProviderType.githubCopilot,
)


private fun providerIcon(type: ProviderType): Pair<ImageVector, Color> = when (type) {
    ProviderType.openAI -> Icons.Default.Hub to Color(0xFF4CAF50)
    ProviderType.anthropic -> Icons.Default.AutoAwesome to Color(0xFFAB47BC)
    ProviderType.gemini -> Icons.Default.Diamond to Color(0xFF42A5F5)
    ProviderType.openRouter -> Icons.Default.AltRoute to Color(0xFF00BCD4)
    ProviderType.xAI -> Icons.Default.FlashOn to Color(0xFFFF7043)
    ProviderType.kimiCode -> Icons.Default.Terminal to Color(0xFF5C6BC0)
    ProviderType.githubCopilot -> Icons.Default.Terminal to Color(0xFF6E5494)
    ProviderType.openAIResponses -> Icons.Default.Hub to Color(0xFF4CAF50)
    ProviderType.workBuddy -> Icons.Default.Person to Color(0xFF4D6BFE)
    ProviderType.qoder -> Icons.Default.Cloud to Color(0xFF111111)
    ProviderType.minimaxCode -> Icons.Default.Cloud to Color(0xFFE73562)
    ProviderType.antigravity,
    ProviderType.unsupported -> Icons.Default.Cloud to Color(0xFF9E9E9E)
}

private fun availableCredentials(type: ProviderType): List<ProviderCredential> {
    return when (type) {
        ProviderType.openRouter -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.anthropic,
        ProviderType.openAI -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.gemini -> listOf(ProviderCredential.apiKey)
        ProviderType.xAI -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        ProviderType.kimiCode -> listOf(ProviderCredential.oauth, ProviderCredential.apiKey)
        ProviderType.githubCopilot -> listOf(ProviderCredential.oauth)
        ProviderType.openAIResponses -> listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
        ProviderType.workBuddy -> listOf(ProviderCredential.oauth)
        ProviderType.qoder, ProviderType.minimaxCode -> listOf(ProviderCredential.oauth)
        ProviderType.antigravity,
        ProviderType.unsupported -> listOf(ProviderCredential.apiKey)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseProviderScreen(
    onBack: () -> Unit,
    onSelect: (ProviderType) -> Unit,
    onSelectVoiceTemplate: (com.openminis.app.data.model.VoiceProviderTemplate) -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(R.string.provider_list_add_provider),
        onBack = onBack,
    ) {
        val context = LocalContext.current
        val offeredTypes = providerDisplayOrder
        SettingsSection(
            header = stringResource(R.string.add_provider_choose_provider),
            footer = stringResource(R.string.add_provider_you_can_add_multiple_instances_of_the_sa),
        ) {
            offeredTypes.forEachIndexed { index, type ->
                val displayTitle = when (type) {
                    ProviderType.openAI -> "OpenAI / Compatible API"
                    ProviderType.anthropic -> "Anthropic / Compatible API"
                    ProviderType.gemini -> "Google Gemini"
                    ProviderType.openRouter -> "OpenRouter"
                    ProviderType.xAI -> "xAI (Grok)"
                    ProviderType.kimiCode -> "Kimi Code"
                    ProviderType.githubCopilot -> "GitHub Copilot"
                    ProviderType.openAIResponses,
                    ProviderType.antigravity,
                    ProviderType.unsupported -> type.displayName
                    ProviderType.workBuddy -> "WorkBuddy"
                    ProviderType.qoder -> "Qoder"
                    ProviderType.minimaxCode -> "MiniMax Code"
                }
                val subtitleRes = when (type) {
                    ProviderType.openAI -> R.string.add_provider_subtitle_openai
                    ProviderType.anthropic -> R.string.add_provider_subtitle_anthropic
                    ProviderType.gemini -> R.string.add_provider_subtitle_gemini
                    ProviderType.openRouter -> R.string.add_provider_subtitle_openrouter
                    ProviderType.xAI -> R.string.add_provider_subtitle_xai
                    ProviderType.kimiCode -> R.string.add_provider_subtitle_kimi
                    ProviderType.githubCopilot -> R.string.add_provider_subtitle_copilot
                    ProviderType.openAIResponses -> R.string.add_provider_subtitle_openai
                    ProviderType.workBuddy -> R.string.add_provider_subtitle_openai
                    ProviderType.qoder, ProviderType.minimaxCode -> R.string.add_provider_subtitle_openai
                    ProviderType.antigravity,
                    ProviderType.unsupported -> R.string.add_provider_subtitle_openai
                }
                val (icon, iconColor) = providerIcon(type)
                SettingsRow(
                    title = displayTitle,
                    subtitle = stringResource(subtitleRes),
                    icon = icon,
                    iconColor = iconColor,
                    onClick = { onSelect(type) },
                    showDivider = index < offeredTypes.size - 1,
                )
            }
        }

        val templates = com.openminis.app.data.model.VoiceProviderTemplate.all
        val templateNotes = templates.mapNotNull { it.note }
        SettingsSection(
            header = stringResource(R.string.add_provider_voice_chat_providers),
            footer = (
                listOf(stringResource(R.string.add_provider_voice_templates_footer)) + templateNotes
                ).joinToString("\n"),
        ) {
            templates.forEachIndexed { index, template ->
                val capabilityRes = when (template.capability) {
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.TTS ->
                        R.string.add_provider_voice_capability_tts
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.ASR ->
                        R.string.add_provider_voice_capability_asr
                    com.openminis.app.data.model.VoiceProviderTemplate.Capability.BOTH ->
                        R.string.add_provider_voice_capability_both
                }
                SettingsRow(
                    title = template.name,
                    subtitle = stringResource(capabilityRes),
                    icon = Icons.Outlined.GraphicEq,
                    onClick = { onSelectVoiceTemplate(template) },
                    showDivider = index < templates.size - 1,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseCredentialScreen(
    providerType: ProviderType,
    onBack: () -> Unit,
    onSelect: (ProviderCredential) -> Unit,
) {
    val credentials = availableCredentials(providerType)

    SettingsScaffold(
        title = stringResource(R.string.add_provider_auth_method),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_choose_authentication),
            footer = stringResource(R.string.add_provider_pick_the_auth_method_that_matches_your_a),
        ) {
            credentials.forEachIndexed { index, credential ->
                val (title, description, icon) = when (credential) {
                    ProviderCredential.apiKey -> Triple(
                        stringResource(R.string.provider_list_api_key),
                        apiKeyDescription(providerType),
                        Icons.Default.Key,
                    )
                    ProviderCredential.oauth -> Triple(
                        "OAuth",
                        oauthDescription(providerType),
                        Icons.Default.Person,
                    )
                }
                SettingsRow(
                    title = title,
                    subtitle = description,
                    icon = icon,
                    onClick = { onSelect(credential) },
                    showDivider = index < credentials.size - 1,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun apiKeyDescription(type: ProviderType): String = when (type) {
    ProviderType.openAI -> "Supports OpenAI official API and compatible third-party endpoints"
    ProviderType.anthropic -> "Use an API key from your Anthropic account"
    ProviderType.gemini -> "Use an API key from your Google Gemini account"
    ProviderType.openRouter -> "Use an API key from your OpenRouter account"
    ProviderType.xAI -> "Use an API key from your xAI Console (api.x.ai)"
    ProviderType.kimiCode -> "Use an API key from your Moonshot account"
    ProviderType.githubCopilot -> "GitHub Copilot requires signing in with GitHub"
    ProviderType.openAIResponses -> "Supports the OpenAI Responses API and compatible endpoints"
    ProviderType.workBuddy -> "WorkBuddy uses account sign-in; no API key needed"
    ProviderType.antigravity,
    ProviderType.qoder, ProviderType.minimaxCode -> "This platform uses account sign-in; no API key is needed"
    ProviderType.unsupported -> "This provider type is not supported on Android"
}

private fun oauthDescription(type: ProviderType): String = when (type) {
    ProviderType.anthropic -> "Sign in with your Claude account"
    ProviderType.gemini -> "Sign in with Google for Cloud Code Assist"
    ProviderType.openAI -> "Sign in with OpenAI Codex"
    ProviderType.xAI -> "Sign in with xAI (requires SuperGrok or X Premium+)"
    ProviderType.openRouter -> "Sign in with OpenRouter"
    ProviderType.kimiCode -> "Sign in with your Kimi account (Coding Plan)"
    ProviderType.githubCopilot -> "Sign in with GitHub (requires a Copilot subscription)"
    ProviderType.openAIResponses -> "Sign in with OpenAI Codex"
    ProviderType.workBuddy -> "Sign in with your WorkBuddy / CodeBuddy account to use its included model quota"
    ProviderType.qoder -> "Sign in with your Qoder account to use its included model quota"
    ProviderType.minimaxCode -> "Sign in with your MiniMax account (M Plan) to use its included quota"
    ProviderType.antigravity,
    ProviderType.unsupported -> "This provider type is not supported on Android"
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureProviderScreen(
    providerType: ProviderType,
    credentialType: ProviderCredential,
    providerRepository: ProviderRepository,
    voiceTemplate: com.openminis.app.data.model.VoiceProviderTemplate? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val defaultLabel = remember(config) {
        val baseName = voiceTemplate?.name ?: providerType.displayName
        val existingLabels = config.instances.map { it.label }.toSet()
        if (baseName !in existingLabels) baseName
        else {
            var n = 2
            while ("$baseName $n" in existingLabels) n++
            "$baseName $n"
        }
    }

    var label by remember { mutableStateOf(defaultLabel) }
    var labelEdited by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(defaultLabel, labelEdited) {
        if (!labelEdited) label = defaultLabel
    }
    var apiKey by remember { mutableStateOf("") }
    var customBaseURL by remember { mutableStateOf(voiceTemplate?.baseURL ?: "") }

    SettingsScaffold(
        title = stringResource(R.string.add_provider_configure_provider, providerType.displayName),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_provider_the_label_is_shown_in_the_provider_list_),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.provider_detail_label))
                SectionTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        labelEdited = true
                    },
                    placeholder = providerType.displayName,
                    singleLine = true,
                )
            }
        }

        when (credentialType) {
            ProviderCredential.apiKey -> ApiKeyConfigSection(
                providerType = providerType,
                label = label,
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                customBaseURL = customBaseURL,
                onCustomBaseURLChange = { customBaseURL = it },
                providerRepository = providerRepository,
                initialAppendV1 = voiceTemplate?.appendV1,
                onSaved = onSaved,
            )
            ProviderCredential.oauth -> OAuthConfigSection(
                providerType = providerType,
                label = label,
                providerRepository = providerRepository,
                onSaved = onSaved,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.ApiKeyConfigSection(
    providerType: ProviderType,
    label: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customBaseURL: String,
    onCustomBaseURLChange: (String) -> Unit,
    providerRepository: ProviderRepository,
    initialAppendV1: Boolean? = null,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showApiKeyPlaintext by remember { mutableStateOf(false) }
    var appendV1Suffix by remember {
        mutableStateOf(initialAppendV1 ?: (providerType != ProviderType.gemini))
    }
    var useResponsesAPI by remember { mutableStateOf(false) }

    val keyPlaceholder = when (providerType) {
        ProviderType.anthropic -> "sk-ant-..."
        ProviderType.openAI -> "sk-..."
        ProviderType.gemini -> "Gemini API Key..."
        ProviderType.openRouter -> "sk-or-..."
        ProviderType.xAI -> "xai-..."
        ProviderType.kimiCode -> "sk-..."
        ProviderType.githubCopilot -> ""
        ProviderType.openAIResponses -> "sk-..."
        ProviderType.workBuddy -> ""
        ProviderType.qoder, ProviderType.minimaxCode -> ""
        ProviderType.antigravity,
        ProviderType.unsupported -> "API Key..."
    }
    SettingsSection(
        header = stringResource(R.string.add_provider_credential),
        footer = stringResource(R.string.add_provider_your_key_is_stored_securely_in_encrypted),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.provider_list_api_key))
            SectionTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = keyPlaceholder,
                singleLine = true,
                visualTransformation = if (showApiKeyPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKeyPlaintext = !showApiKeyPlaintext }) {
                        Icon(
                            if (showApiKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKeyPlaintext) "Hide" else "Show",
                        )
                    }
                },
            )
        }
    }

    if (providerType != ProviderType.openRouter) {
        val defaultUrl = when (providerType) {
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.openAI -> "https://api.openai.com"
            else -> "https://api.example.com"
        }
        val baseUrlFooter = if (providerType == ProviderType.gemini) {
            "Leave empty to use the default Google endpoint. Enter the full base URL including version path."
        } else if (providerType == ProviderType.anthropic) {
            stringResource(R.string.add_provider_endpoint_anthropic_hint)
        } else if (appendV1Suffix) {
            "Leave empty to use the default endpoint. \"/v1\" is appended automatically — enter the base host only."
        } else {
            "The URL is used verbatim. Include the full path up to (but not including) the endpoint."
        }
        SettingsSection(
            header = stringResource(R.string.add_provider_endpoint),
            footer = baseUrlFooter,
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
                SectionTextField(
                    value = customBaseURL,
                    onValueChange = onCustomBaseURLChange,
                    placeholder = defaultUrl,
                    singleLine = true,
                )
            }
            if (providerType != ProviderType.gemini) {
                SettingsSwitchRow(
                    title = stringResource(R.string.add_provider_auto_append_v1_quoted),
                    checked = appendV1Suffix,
                    onCheckedChange = { appendV1Suffix = it },
                    showDivider = false,
                )
            }
        }
    }

    if (providerType == ProviderType.openAI) {
        SettingsSection(
            header = stringResource(R.string.provider_detail_api_format),
            footer = if (useResponsesAPI) {
                stringResource(R.string.provider_detail_api_format_footer_responses)
            } else {
                stringResource(R.string.provider_detail_api_format_footer_chat)
            },
        ) {
            SettingsCardBlock {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useResponsesAPI,
                        onClick = { useResponsesAPI = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                    SegmentedButton(
                        selected = useResponsesAPI,
                        onClick = { useResponsesAPI = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    val saveOnce = rememberSaveOnce()
    MinisButton(
        onClick = {
            if (!saveOnce.claim()) return@MinisButton
            val trimmedBase = customBaseURL.trim()
            val instance = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { providerType.displayName },
                providerType = providerType,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = trimmedBase.ifEmpty { null },
                appendV1Suffix = appendV1Suffix,
                useResponsesAPI = providerType == ProviderType.openAI && useResponsesAPI,
            )
            providerRepository.addInstance(instance)
            if (apiKey.isNotBlank()) {
                providerRepository.saveApiKey(instance.id, apiKey.trim())
            }
            providerRepository.triggerAsyncModelReconcile(instance.id)
            onSaved()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        enabled = apiKey.isNotBlank() || (
            customBaseURL.isNotBlank() &&
                (providerType == ProviderType.openAI || providerType == ProviderType.anthropic)
        ),
    ) {
        Text(stringResource(R.string.provider_list_add_provider))
    }
}

@Composable
private fun ColumnScope.OAuthConfigSection(
    providerType: ProviderType,
    label: String,
    providerRepository: ProviderRepository,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingInstanceId = remember { UUID.randomUUID().toString() }
    var isAuthenticating by remember { mutableStateOf(false) }
    var isAuthenticated by remember { mutableStateOf(false) }
    var maskedToken by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var manualToken by remember { mutableStateOf("") }
    var customBaseURL by remember { mutableStateOf("") }
    var appendV1Suffix by remember { mutableStateOf(providerType != ProviderType.gemini) }

    val signInLabel = when (providerType) {
        ProviderType.anthropic -> "Sign in with Claude"
        ProviderType.gemini -> "Sign in with Google"
        ProviderType.openAI -> "Sign in with OpenAI"
        ProviderType.openRouter -> "Sign in with OpenRouter"
        ProviderType.xAI -> "Sign in with xAI"
        ProviderType.kimiCode -> "Sign in with Kimi Code"
        ProviderType.githubCopilot -> "Sign in with GitHub"
        ProviderType.openAIResponses -> "Sign in with OpenAI"
        ProviderType.workBuddy -> "Sign in with WorkBuddy"
        ProviderType.qoder -> "Sign in with Qoder"
        ProviderType.minimaxCode -> "Sign in with MiniMax"
        ProviderType.antigravity,
        ProviderType.unsupported -> "Sign in"
    }

    var kimiDeviceAuth by remember {
        mutableStateOf<com.openminis.app.auth.KimiDeviceFlow.DeviceAuthorization?>(null)
    }
    var kimiLoginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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

    var copilotDeviceAuth by remember {
        mutableStateOf<com.openminis.app.auth.CopilotDeviceFlow.DeviceAuthorization?>(null)
    }
    var showCopilotDisclaimer by remember { mutableStateOf(false) }

    copilotDeviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.verificationUri,
            onCancel = {
                kimiLoginJob?.cancel()
                copilotDeviceAuth = null
            },
            title = stringResource(R.string.copilot_login_title),
            instructions = stringResource(R.string.copilot_login_instructions),
        )
    }

    if (isAuthenticated) {
        SettingsSection(
            header = stringResource(R.string.add_provider_authentication),
            footer = stringResource(R.string.add_provider_sign_in_succeeded_the_token_is_stored_in),
        ) {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF34C759),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_provider_authenticated), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                if (maskedToken != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.add_provider_token), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        maskedToken!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val saveOnce = rememberSaveOnce()
        MinisButton(
            onClick = {
                if (!saveOnce.claim()) return@MinisButton
                val instance = ProviderInstance(
                    id = pendingInstanceId,
                    label = label.ifBlank { providerType.displayName },
                    providerType = providerType,
                    credentialType = ProviderCredential.oauth,
                )
                providerRepository.addInstance(instance)
                providerRepository.triggerAsyncModelReconcile(instance.id)
                onSaved()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.add_provider_save_provider))
        }
    } else {
        SettingsSection(
            header = stringResource(R.string.provider_detail_sign_in),
            footer = stringResource(R.string.add_provider_opens_the_provider_s_web_sign_in_flow_af),
        ) {
            SettingsCardBlock {
                val beginSignIn: () -> Unit = {
                    isAuthenticating = true
                    errorMessage = null
                    kimiLoginJob = scope.launch {
                            try {
                                when (providerType) {
                                    ProviderType.kimiCode -> {
                                        val key = com.openminis.app.auth.KimiOAuthManager.login(
                                            context, pendingInstanceId, providerRepository,
                                            onDeviceCode = { auth -> kimiDeviceAuth = auth },
                                        )
                                        kimiDeviceAuth = null
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.githubCopilot -> {
                                        val key = com.openminis.app.auth.CopilotOAuthManager.login(
                                            context, pendingInstanceId,
                                            onDeviceCode = { auth -> copilotDeviceAuth = auth },
                                        )
                                        copilotDeviceAuth = null
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.openRouter -> {
                                        val key = OpenRouterOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.anthropic -> {
                                        val key = com.openminis.app.auth.ClaudeOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.openAI -> {
                                        val key = OpenAIOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.xAI -> {
                                        val key = com.openminis.app.auth.XAIOAuthManager.login(context, pendingInstanceId, providerRepository)
                                        maskedToken = maskOAuthToken(key)
                                    }
                                    ProviderType.workBuddy -> {
                                        val manager = com.openminis.app.auth.WorkBuddyOAuthManager(context, pendingInstanceId)
                                        val account = manager.login(
                                            repo = providerRepository,
                                            region = "domestic",
                                            onAuthUrl = { session ->
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(session.authUrl),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                runCatching { context.startActivity(intent) }
                                            },
                                        )
                                        maskedToken = maskOAuthToken(account.accessToken)
                                    }
                                    else -> {
                                        throw Exception("OAuth not yet implemented for ${providerType.displayName}")
                                    }
                                }
                                isAuthenticated = true
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                kimiDeviceAuth = null
                                copilotDeviceAuth = null
                                throw e
                            } catch (e: Exception) {
                                kimiDeviceAuth = null
                                copilotDeviceAuth = null
                                android.util.Log.e(
                                    "AddProviderOAuth",
                                    "OAuth sign-in failed for $providerType: " +
                                        "${e.javaClass.name}: ${e.message}",
                                    e,
                                )
                                errorMessage = if (e is com.openminis.app.auth.OAuthNetworkUnreachableException) {
                                    context.getString(R.string.add_provider_oauth_network_unreachable)
                                } else {
                                    e.message ?: "${e.javaClass.simpleName}: authentication failed"
                                }
                            } finally {
                                isAuthenticating = false
                            }
                    }
                }

                if (showCopilotDisclaimer) {
                    CopilotDisclaimerDialog(
                        onAccept = {
                            com.openminis.app.util.CopilotFeatureFlag.recordConsent(context)
                            showCopilotDisclaimer = false
                            beginSignIn()
                        },
                        onCancel = { showCopilotDisclaimer = false },
                    )
                }

                MinisButton(
                    onClick = {
                        if (providerType == ProviderType.githubCopilot &&
                            !com.openminis.app.util.CopilotFeatureFlag.hasAcceptedConsent(context)
                        ) {
                            showCopilotDisclaimer = true
                        } else {
                            beginSignIn()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAuthenticating,
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_provider_signing_in))
                    } else {
                        Text(signInLabel)
                    }
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (providerType != ProviderType.githubCopilot) {
        val defaultUrl = when (providerType) {
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.openAI -> "https://api.openai.com"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
            ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
            ProviderType.openAIResponses -> "https://api.openai.com"
            ProviderType.workBuddy -> com.openminis.app.auth.WorkBuddyApi.DOMESTIC_BACKEND
            ProviderType.qoder -> com.openminis.app.auth.QoderApi.DEFAULT_CHAT_BASE
            ProviderType.minimaxCode -> com.openminis.app.auth.MiniMaxCodeApi.DEFAULT_CHAT_BASE
            ProviderType.antigravity,
            ProviderType.unsupported -> ""
        }
        SettingsSection(
            header = stringResource(R.string.add_provider_or_configure_manually),
            footer = stringResource(R.string.add_provider_for_third_party_coding_plans_e_g_minimax),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
                SectionTextField(
                    value = customBaseURL,
                    onValueChange = { customBaseURL = it },
                    placeholder = defaultUrl,
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.add_provider_bearer_token))
                SectionTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (providerType != ProviderType.gemini) {
                SettingsSwitchRow(
                    title = stringResource(R.string.add_provider_auto_append_v1_quoted),
                    checked = appendV1Suffix,
                    onCheckedChange = { appendV1Suffix = it },
                    showDivider = false,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        val saveOnce = rememberSaveOnce()
        MinisButton(
            onClick = {
                if (!saveOnce.claim()) return@MinisButton
                val trimmedBase = customBaseURL.trim()
                val instance = ProviderInstance(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { providerType.displayName },
                    providerType = providerType,
                    credentialType = ProviderCredential.oauth,
                    customBaseURL = trimmedBase.ifEmpty { null },
                    appendV1Suffix = appendV1Suffix,
                )
                providerRepository.addInstance(instance)
                providerRepository.saveApiKey(instance.id, manualToken.trim())
                providerRepository.triggerAsyncModelReconcile(instance.id)
                onSaved()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            enabled = manualToken.isNotBlank(),
        ) {
            Text(stringResource(R.string.provider_list_add_provider))
        }
        }
    }
}

private fun maskOAuthToken(token: String): String {
    if (token.length <= 4) return "*".repeat(token.length)
    val edge = minOf(10, token.length / 3)
    return token.take(edge) + "*".repeat(10) + token.takeLast(edge)
}

internal class SaveOnce {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

@Composable
private fun rememberSaveOnce(): SaveOnce = remember { SaveOnce() }
