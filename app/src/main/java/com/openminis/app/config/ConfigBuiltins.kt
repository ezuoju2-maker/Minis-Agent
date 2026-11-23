package com.openminis.app.config

import android.content.Context
import com.openminis.app.config.collections.EnvVarsCollection
import com.openminis.app.service.DynamicIslandSupport
import com.openminis.app.config.collections.GroupsCollection
import com.openminis.app.config.collections.ModelsCollection
import com.openminis.app.config.collections.ProvidersCollection
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.config.fields.PrefsDoubleField
import com.openminis.app.config.fields.PrefsEnumField
import com.openminis.app.config.fields.PrefsIntCodedEnumField
import com.openminis.app.config.fields.PrefsIntField
import com.openminis.app.config.fields.PrefsLongField
import com.openminis.app.config.fields.PrefsStringField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigReadableStrings
import com.openminis.app.config.readable.ConfigRefKind
import com.openminis.app.config.readable.ConfigRefResolver
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableThinkingLabels
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ConfigBuiltins {

    fun registerInto(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
        chatRepo: ChatRepository,
    ) {
        installReadable(context, providerRepo)
        registerSelfMeta(r)
        registerSession(r, providerRepo, chatRepo)
        registerAppearance(r, context)
        registerChat(r, context)
        registerBackground(r, context)
        registerLogs(r, context)
        registerProviderCollections(r, context, providerRepo, envVarRepo)
        registerDefaults(r, providerRepo)
        registerSoul(r, context)
        registerMemory(r, context)
        registerNetwork(r, context, envVarRepo)
        registerAgentTools(r, context)
        registerSpeech(r, context)
        registerScheduled(r, context)
        registerUpdates(r, context)
        registerBackup(r, context)
        registerFiles(r, context)
    }


    private fun installReadable(context: Context, providerRepo: ProviderRepository) {
        val app = context.applicationContext
        ConfigRefResolver.names = com.openminis.app.config.readable.ProviderRepositoryNames(providerRepo)
        ConfigReadableRenderer.localizerFactory = {
            val ctx = com.openminis.app.i18n.LocaleWrap.wrap(app)
            val lookup: (String) -> String? = { key -> ConfigReadableStrings.lookup(ctx, key) }
            lookup
        }
        ConfigReadableRenderer.currentLocale = {
            com.openminis.app.i18n.LocaleWrap.wrap(app).resources.configuration.locales[0] ?: Locale.getDefault()
        }
        ConfigReadableRenderer.currentLanguage = { ConfigReadableRenderer.currentLocale().toLanguageTag() }
    }

    private val fontScaleLabels: Map<String, String> = mapOf(
        "xSmall" to "Extra small", "small" to "Small", "default" to "Default",
        "medium" to "Medium", "large" to "Large", "extraLarge" to "Extra large",
    )

    private fun languagePhrase(v: ConfigValue): ReadableArg {
        val code = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        if (code.isEmpty()) return ReadableArg.Key("System")
        val name = Locale.forLanguageTag(code.replace('_', '-')).getDisplayName(ConfigReadableRenderer.currentLocale())
        return ReadableArg.Text(name.ifBlank { code })
    }


    private fun registerNetwork(r: ConfigRegistry, context: Context, envVarRepo: EnvVarRepository) {
        val fields = com.openminis.app.config.fields.ContainerDnsFields.build(ContainerDns.PrefsStore(context)) {
            runCatching { com.openminis.app.sandbox.RootfsManager.getInstance(context).refreshDns() }
        }
        fields.forEach { r.register(it) }
        com.openminis.app.config.fields.NetworkConfigFields.build(envVarRepo).forEach { r.register(it) }
        r.register(com.openminis.app.config.collections.ProxiesCollection(envVarRepo))
    }


    private fun registerAgentTools(r: ConfigRegistry, context: Context) {
        val spec = com.openminis.app.agent.userask.UserAskSettingsSpec
        val prefs = context.getSharedPreferences(
            com.openminis.app.tools.AgentToolSwitch.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "agentTools.userAsk.enabled",
                displayName = "User interaction tool (user_ask)",
                description = "Whether the assistant may ask you structured questions (choices, or a confirmation before a risky action) on an in-chat card. Never offered to sub agents. In a run you are not watching, the card stays open and your answer is sent as your next message. When off, the tool is removed.",
                prefs = prefs,
                key = spec.ENABLED_KEY,
                defaultValue = spec.DEFAULT_ENABLED,
            )
        )
        r.register(
            PrefsIntField(
                path = "agentTools.userAsk.timeoutMinutes",
                displayName = "Question timeout (minutes)",
                description = "Minutes the assistant waits for an answer. After that the question is returned to the assistant as timed out - nothing is applied, approved or cancelled on your behalf. 0 = wait indefinitely. Default ${spec.DEFAULT_TIMEOUT_MINUTES}.",
                prefs = prefs,
                key = spec.TIMEOUT_MINUTES_KEY,
                defaultValue = spec.DEFAULT_TIMEOUT_MINUTES,
                minValue = 0,
                maxValue = spec.MAX_TIMEOUT_MINUTES,
                zeroLabel = "Wait indefinitely",
            )
        )
        r.register(
            PrefsBoolField(
                path = "agentTools.userAsk.notifyInBackground",
                displayName = "Notify about questions in the background",
                description = "Post a notification when the assistant asks while Minis is in the background. The question can be answered from the notification.",
                prefs = prefs,
                key = spec.NOTIFY_IN_BACKGROUND_KEY,
                defaultValue = spec.DEFAULT_NOTIFY_IN_BACKGROUND,
            )
        )
    }

    private fun registerSpeech(r: ConfigRegistry, context: Context) {
        val state = com.openminis.app.speech.VoiceOutputState
        r.register(
            ClosureField(
                path = "speech.volume",
                displayName = "Speech volume",
                description = "Read-aloud volume, 0.0 (mute) to 2.0 (200%); 1.0 (default) is the system media volume as is. Applied to both the system voice and provider TTS; above 1.0 a limiter-backed loudness boost makes quiet voices louder.",
                valueSchema = ConfigSchema.Double(min = 0.0, max = 2.0),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Number()),
                reader = {
                    state.init(context)
                    ConfigValue.Double(Math.round(state.volume.value * 100.0) / 100.0)
                },
                writer = { v ->
                    val d = when (v) {
                        is ConfigValue.Double -> v.value
                        is ConfigValue.Int -> v.value.toDouble()
                        else -> throw ConfigError.TypeMismatch("double")
                    }
                    state.init(context)
                    state.setVolume(d.toFloat())
                },
            )
        )
    }

    private fun registerScheduled(r: ConfigRegistry, context: Context) {
        val store = com.openminis.app.scheduled.ScheduledTaskStore(context.applicationContext)
        r.register(
            ClosureField(
                path = "scheduled.showHomeBadge",
                displayName = "Show scheduled task count on Home",
                description = "Whether the clock icon on the home screen shows the number of enabled scheduled tasks. Default true. It only hides the count; tasks keep running. Tasks themselves are managed with the minis-scheduled command, not here.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = { ConfigValue.Bool(store.showHomeBadge()) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                    store.setShowHomeBadge(b)
                },
            )
        )
    }

    private fun registerUpdates(r: ConfigRegistry, context: Context) {
        val app = context.applicationContext
        val store = com.openminis.app.data.UpdateStateStore
        val channels = com.openminis.app.data.UpdatePolicy.Channel.entries
        r.register(
            ClosureField(
                path = "updates.channel",
                displayName = "Update channel",
                description = "Which releases the in-app updater offers: stable (tested releases, recommended) or beta (earlier builds with new features; may be less stable). Switching back to stable keeps the installed beta until a newer stable release exists. Changing it only changes what is OFFERED - installing an update is always the user's own tap.",
                valueSchema = ConfigSchema.StrEnum(channels.map { it.id }),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("stable" to "Stable", "beta" to "Beta"))),
                reader = {
                    store.init(app)
                    ConfigValue.Str(store.channel.value.id)
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val ch = channels.firstOrNull { it.id == s }
                        ?: throw ConfigError.InvalidValue("must be one of: ${channels.joinToString(", ") { it.id }}")
                    store.setChannel(app, ch)
                    runCatching { com.openminis.app.data.UpdateAutoChecker.maybeCheck(app, "channel") }
                },
            )
        )
        val autoCheck = ClosureField(
            path = "updates.autoCheck",
            displayName = "Check for updates automatically",
            description = "Whether Minis looks for a new version by itself when it comes to the foreground (at most once a day on stable, every 6 hours on beta). false stops those requests and hides the update badges; Check for Updates in Settings still works.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = {
                store.init(app)
                ConfigValue.Bool(store.autoEnabled.value)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                store.setAutoEnabled(app, b)
            },
        )
        r.register(
            if (com.openminis.app.BuildConfig.AUTO_UPDATE_CHECK) {
                autoCheck
            } else {
                UnavailableField(autoCheck, "This build of Minis does not check for updates by itself.")
            }
        )
    }

    private fun registerFiles(r: ConfigRegistry, context: Context) {
        val vm = com.openminis.app.ui.sandbox.FileBrowserViewModel
        r.register(
            PrefsBoolField(
                path = "files.showHidden",
                displayName = "Show Hidden Files",
                description = "Whether the in-app file browser lists dot-files and dot-folders (.git, .env, …). Default false. Only changes what the browser shows; the shell always sees them. A file browser that is already open picks it up the next time it is opened.",
                prefs = context.getSharedPreferences(vm.PREFS_NAME, Context.MODE_PRIVATE),
                key = vm.PREF_KEY_SHOW_HIDDEN,
                defaultValue = false,
            )
        )
    }

    private fun registerBackup(r: ConfigRegistry, context: Context) {
        val app = context.applicationContext
        val identity = com.openminis.app.data.DeviceIdentity
        r.register(
            ClosureField(
                path = "backup.deviceName",
                displayName = "Backup device name",
                description = "The name that leads every backup file name and is recorded in the backup, so two devices backing up into one folder can be told apart. Empty string (default) = automatic: the device's own name. At most ${identity.CUSTOM_NAME_MAX_LENGTH} characters. Backup destinations and their credentials are not configurable here.",
                valueSchema = ConfigSchema.Str(maxLength = identity.CUSTOM_NAME_MAX_LENGTH),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text(empty = "Automatic")),
                reader = { ConfigValue.Str(identity.customName(app) ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    identity.setCustomName(app, s)
                },
            )
        )
    }


    private fun registerMemory(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("minis_memory_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "memory.enabled",
                displayName = "Memory enabled (global default)",
                description = "Default for whether NEW chat sessions start with memory on. When on, a new session auto-injects GLOBAL.md + recent daily logs into the system prompt and exposes the memory_get / memory_write tools. When off, new sessions get neither. Already-open sessions keep their own per-session setting (toggle in the session memory sheet) and are not changed by this.",
                prefs = prefs,
                key = "memory.global.enabled",
                defaultValue = true,
            )
        )
    }


    private fun registerSelfMeta(r: ConfigRegistry) {
        r.register(
            ClosureField(
                path = "permissions.minisConfig.enabled",
                displayName = "Allow minis-config",
                description = "Master switch. Read-only here — toggle via Settings → Permissions.",
                valueSchema = ConfigSchema.Bool,
                access = ConfigAccess.READONLY,
                risk = ConfigRisk.DESTRUCTIVE,
                revertable = false,
                readable = ConfigReadable.notWritable,
                reader = { ConfigValue.Bool(MinisConfigPermissionStore.isEnabled) },
                writer = { _ ->
                    throw ConfigError.PermissionDenied(
                        "Master switch — toggle via Settings → Permissions only"
                    )
                },
            )
        )
    }


    private fun registerSession(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        chatRepo: ChatRepository,
    ) {
        r.register(
            ClosureField(
                path = "session.thinkingLevel",
                displayName = "Thinking level (current session)",
                description = "off / low / medium / high / xhigh. Applied to the active chat.",
                valueSchema = ConfigSchema.StrEnum(
                    listOf("off", "low", "medium", "high", "xhigh")
                ),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(ReadableThinkingLabels.levels)),
                reader = {
                    val sid = ChatViewModelStore.activeSessionId
                    if (sid == null) ConfigValue.Null
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        val raw = session?.thinkingOverride
                        val token = if (raw == null) "off" else thinkingLevelToToken(
                            ThinkingLevel.parseOrNull(raw) ?: ThinkingLevel.OFF
                        )
                        ConfigValue.Str(token)
                    }
                },
                writer = { v ->
                    val token = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val level = thinkingLevelFromToken(token)
                        ?: throw ConfigError.InvalidValue("Unknown thinking level: $token")
                    val sid = ChatViewModelStore.activeSessionId
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    runBlocking { chatRepo.dao.updateThinkingOverride(sid, level.name) }
                },
            )
        )
        r.register(
            ClosureField(
                path = "session.primaryModel",
                displayName = "Primary model (current session)",
                description = "Set as `entry:<uuid>` or `group:<id>` (use `models` / `groups` topics to discover ids). Empty clears the binding.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.ModelSource)),
                reader = {
                    val sid = ChatViewModelStore.activeSessionId
                    if (sid == null) ConfigValue.Str("")
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        ConfigValue.Str(formatBinding(session?.modelBinding))
                    }
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val sid = ChatViewModelStore.activeSessionId
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    if (s.isEmpty()) {
                        runBlocking { chatRepo.dao.updateSessionBinding(sid, "", "") }
                        return@ClosureField
                    }
                    val cfg = providerRepo.config.value
                    val (bindingJson, modelId) = when {
                        s.startsWith("entry:") -> {
                            val uuid = s.removePrefix("entry:")
                            if (uuid.isEmpty()) {
                                throw ConfigError.InvalidValue("entry uuid is empty")
                            }
                            val entry = cfg.modelEntries.find { it.id == uuid }
                                ?: throw ConfigError.InvalidValue("Unknown model entry uuid: $uuid")
                            """{"type":"entry","entryId":"$uuid"}""" to entry.baseModel.id
                        }
                        s.startsWith("group:") -> {
                            val gid = s.removePrefix("group:")
                            if (gid.isEmpty()) {
                                throw ConfigError.InvalidValue("group id is empty")
                            }
                            val group = cfg.modelGroups.find { it.id == gid }
                                ?: throw ConfigError.InvalidValue("Unknown group id: $gid")
                            val firstEntryId = group.memberEntryIds.firstOrNull()
                                ?: throw ConfigError.InvalidValue("Group $gid has no member entries")
                            val firstEntry = cfg.modelEntries.find { it.id == firstEntryId }
                                ?: throw ConfigError.InvalidValue("Group $gid references missing entry $firstEntryId")
                            """{"type":"group","groupId":"$gid"}""" to firstEntry.baseModel.id
                        }
                        else -> throw ConfigError.InvalidValue(
                            "Expected `entry:<uuid>` or `group:<id>`, got '$s'"
                        )
                    }
                    runBlocking {
                        chatRepo.dao.updateSessionBinding(sid, bindingJson, modelId)
                    }
                },
            )
        )
    }

    private fun thinkingLevelToToken(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF -> "off"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        ThinkingLevel.MAX -> "max"
        ThinkingLevel.ULTRA -> "ultra"
    }

    private fun thinkingLevelFromToken(token: String): ThinkingLevel? = when (token) {
        "off" -> ThinkingLevel.OFF
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        else -> null
    }

    private fun formatBinding(bindingJson: String?): String {
        if (bindingJson.isNullOrEmpty()) return ""
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "entry" -> {
                    val id = obj.optString("entryId")
                    if (id.isEmpty()) "" else "entry:$id"
                }
                "group" -> {
                    val id = obj.optString("groupId")
                    if (id.isEmpty()) "" else "group:$id"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }


    private fun registerAppearance(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.theme",
                displayName = "Theme",
                description = "Light / Dark / System color scheme.",
                prefs = prefs,
                key = "theme_mode",
                cases = listOf("system", "light", "dark"),
                valueLabels = mapOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsBoolField(
                path = "appearance.show_chat_title",
                displayName = "Show chat title pill",
                description = "Show a sticky title pill at the top of the chat while scrolling through history.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE,
                defaultValue = true,
            )
        )
        r.register(
            PrefsStringField(
                path = "appearance.language",
                displayName = "App language",
                description = "BCP-47 code (zh-Hans, en, ja, …) or empty for system.",
                prefs = prefs,
                key = "app_language",
                defaultValue = "",
                maxLength = 16,
                readable = ConfigReadable(ConfigValueFormatter.Custom(::languagePhrase)),
            )
        )
        r.register(fontScaleField(
            path = "appearance.appFontSize",
            displayName = "App font size",
            description = "Base font size for the app's own UI (lists, settings, titles). The chat text has its own two settings: chat.messageFontSize and chat.inputFontSize.",
            prefs = prefs,
            key = com.openminis.app.ui.settings.KEY_FONT_APP_BASE,
        ))
    }


    private fun registerChat(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE)
        val appearancePrefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "chat.returnKey",
                displayName = "Return key behavior",
                description = "What the on-screen Return key does in the chat box.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR,
                cases = listOf("newline", "send"),
                valueLabels = mapOf("newline" to "New line", "send" to "Send"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.keepScreenAwake",
                displayName = "Keep screen awake during tasks",
                description = "Prevents auto-lock while the agent is busy.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE,
                defaultValue = false,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
                displayName = "Tool preview",
                description = "Show live preview during agent tool execution.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_TOOL_PREVIEW,
                defaultValue = true,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.autoExpandThinking",
                displayName = "Expand Thinking While Streaming",
                description = "true (default): a new thinking block opens while the model reasons and collapses when it finishes. false: thinking blocks stay collapsed until tapped. Long-pressing a thinking block switches it too.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING,
                defaultValue = true,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.autoGrouping",
                displayName = "Auto-Grouping",
                description = "When a chat's title is first generated, also file the chat into a matching existing group. Runs once per chat, only uses groups that already exist, and leaves the chat ungrouped when none fits. Default true.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_GROUPING,
                defaultValue = true,
            )
        )
        val speedNames = linkedMapOf(
            com.openminis.app.data.SpeedTier.STANDARD to "standard",
            com.openminis.app.data.SpeedTier.FAST to "fast",
            com.openminis.app.data.SpeedTier.ULTRAFAST to "ultrafast",
        )
        r.register(
            ClosureField(
                path = "chat.speedTier",
                displayName = "Speed",
                description = "Response speed tier requested from models that offer one (ChatGPT sign-in / OpenAI and compatible providers). standard (default) = normal; fast = priority processing; ultrafast = fastest tier, only on models that support it - other models fall back to fast. Faster tiers use more of the account's quota. Applies from the next request.",
                valueSchema = ConfigSchema.StrEnum(speedNames.values.toList()),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("standard" to "Standard", "fast" to "Fast", "ultrafast" to "Ultrafast"))),
                reader = { ConfigValue.Str(speedNames[com.openminis.app.data.FastModePrefs.tier()] ?: "standard") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val tier = speedNames.entries.firstOrNull { it.value == s }?.key
                        ?: throw ConfigError.InvalidValue("must be one of: ${speedNames.values.joinToString(", ")}")
                    com.openminis.app.data.FastModePrefs.setTier(context.applicationContext, tier)
                },
            )
        )
        val toolPrefs = context.getSharedPreferences(
            com.openminis.app.tools.AgentToolSwitch.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "chat.tools.browserUse",
                displayName = "Browser Use tool",
                description = "Whether the assistant is offered browser_use. When off the tool is removed from the schema and the system prompt no longer mentions it — the model never learns it exists. Each tool that is on adds its schema and its prompt section to every request.",
                prefs = toolPrefs,
                key = com.openminis.app.tools.AgentToolSwitch.BROWSER.key,
                defaultValue = com.openminis.app.tools.AgentToolSwitch.BROWSER.defaultValue,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.tools.subAgents",
                displayName = "Sub Agents tool",
                description = "Whether the assistant is offered subagent_task. When off that tool and the sub agent roster both leave the request. Configure the agents themselves under `subagents`.",
                prefs = toolPrefs,
                key = com.openminis.app.tools.AgentToolSwitch.AGENTS.key,
                defaultValue = com.openminis.app.tools.AgentToolSwitch.AGENTS.defaultValue,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.foldTools",
                displayName = "Fold Consecutive Tool Calls",
                description = "Show consecutive tool calls as one tappable line.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_FOLD_TOOLS,
                defaultValue = com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.confirmBeforeStopping",
                displayName = "Confirm before stopping",
                description = "What tapping the composer's stop button does while a task runs. false (default) = stop immediately; true = ask \"Stop this task?\" first. Long-pressing the stop button switches it too. Stopping a conversation with scheduled tasks always asks, either way.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY,
                defaultValue = com.openminis.app.ui.chat.StopConfirmPolicy.DEFAULT_ENABLED,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.autoFocusAfterReply",
                displayName = "Auto-focus input after reply",
                description = "When ON, the keyboard pops up automatically after the model finishes a reply so the input is ready for a follow-up. Turn OFF if you prefer to read the response without an unexpected keyboard.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY,
                defaultValue = true,
            )
        )
        r.register(fontScaleField(
            path = "chat.inputFontSize",
            displayName = "Input font size",
            description = "Font size for the chat composer text editor.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT,
        ))
        r.register(fontScaleField(
            path = "chat.messageFontSize",
            displayName = "Message font size",
            description = "Base font size for assistant / user message bodies (markdown). Setting this invalidates rendered markdown caches.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_MESSAGE,
        ))
    }

    private fun fontScaleField(
        path: String,
        displayName: String,
        description: String,
        prefs: android.content.SharedPreferences,
        key: String,
    ): ConfigField {
        val cases = listOf("xSmall", "small", "default", "medium", "large", "extraLarge")
        return ClosureField(
            path = path,
            displayName = displayName,
            description = description,
            valueSchema = ConfigSchema.StrEnum(cases),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(fontScaleLabels)),
            reader = {
                val level = prefs.getInt(key, 0)
                val token = when (level) {
                    -2 -> "xSmall"
                    -1 -> "small"
                    0 -> "default"
                    1 -> "medium"
                    2 -> "large"
                    3 -> "extraLarge"
                    else -> "default"
                }
                ConfigValue.Str(token)
            },
            writer = { v ->
                val token = (v as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                val level = when (token) {
                    "xSmall" -> -2
                    "small" -> -1
                    "default" -> 0
                    "medium" -> 1
                    "large" -> 2
                    "extraLarge" -> 3
                    else -> throw ConfigError.InvalidValue("Unknown font scale: $token")
                }
                prefs.edit().putInt(key, level).apply()
            },
        )
    }


    private fun registerBackground(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences(BackgroundSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "background.enhanced",
                displayName = "Enhanced background execution",
                description = "Keep agent tasks running when the app is backgrounded.",
                prefs = prefs,
                key = "enhanced_background_execution",
                defaultValue = false,
                risk = ConfigRisk.SENSITIVE,
            )
        )
        r.register(
            PrefsBoolField(
                path = "background.notifications",
                displayName = "Background notifications",
                description = "Post a system notification when long-running tasks complete.",
                prefs = prefs,
                key = BackgroundSettingsRepository.KEY_TASK_NOTIFICATIONS,
                defaultValue = BackgroundSettingsRepository.DEFAULT_TASK_NOTIFICATIONS,
            )
        )
        val dynamicIslandField = PrefsBoolField(
            path = "background.dynamicIsland",
            displayName = "Live Updates (dynamic island)",
            description = "Show agent progress in the Android 16 Live Updates status chip.",
            prefs = prefs,
            key = BackgroundSettingsRepository.KEY_DYNAMIC_ISLAND_ENABLED,
            defaultValue = false,
        )
        r.register(
            if (DynamicIslandSupport.isDynamicIslandCapable(context)) {
                dynamicIslandField
            } else {
                UnavailableField(
                    dynamicIslandField,
                    "Live Updates requires Android 16 or newer plus the per-app " +
                        "Live Updates permission; this device doesn't support it.",
                )
            }
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "background.scheduledCountdown",
                displayName = "Scheduled task countdown",
                description = "How long before a scheduled task's next run its chat shows the countdown in the notification and keeps running in the background.",
                prefs = prefs,
                key = BackgroundSettingsRepository.KEY_SCHEDULED_COUNTDOWN_LEAD,
                cases = BackgroundSettingsRepository.SCHEDULED_COUNTDOWN_CASES,
                valueLabels = mapOf(
                    "off" to "Off", "1m" to "1 minute before", "5m" to "5 minutes before",
                    "15m" to "15 minutes before", "always" to "Whole wait",
                ),
                defaultIndex = BackgroundSettingsRepository.DEFAULT_SCHEDULED_COUNTDOWN_LEAD,
            )
        )
    }


    private fun registerLogs(r: ConfigRegistry, context: Context) {
        r.register(
            ClosureField(
                path = "logs.enabled",
                displayName = "Logging enabled",
                description = "Capture stdout/stderr to daily log files for diagnostics.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = {
                    ConfigValue.Bool(com.openminis.app.logging.AppLogger.isEnabled(context))
                },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.logging.AppLogger.setEnabled(context, b)
                },
            )
        )
    }


    private fun registerProviderCollections(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
    ) {
        r.register(ProvidersCollection(providerRepo, envVarRepo, context.applicationContext))
        r.register(com.openminis.app.config.collections.SubAgentsCollection(providerRepo))
        r.register(com.openminis.app.config.collections.SubAgentsCollection.orderField(providerRepo))
        r.register(com.openminis.app.config.collections.SubAgentsCollection.summaryField(providerRepo))
        r.register(ModelsCollection(providerRepo))
        r.register(GroupsCollection(providerRepo))
        r.register(EnvVarsCollection(envVarRepo))
        r.register(com.openminis.app.config.collections.ThinkingRulesCollection(providerRepo))

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        r.register(
            ReadOnlyField(
                path = "providers",
                displayName = "LLM provider instances (summary)",
                description = "Read-only list of configured providers (id, label, type, credential type, enabled, base URL). Credentials are not included. Use `minis-config add providers <json>` to create a new provider (payload mirrors a provider-export `config` block, with optional `apiKey` as literal or `\$\$ENV_VAR`).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val instances = providerRepo.config.value.instances
                    ConfigValue.Arr(
                        instances.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "label" to ConfigValue.Str(inst.label),
                                    "providerType" to ConfigValue.Str(inst.providerType.name),
                                    "credentialType" to ConfigValue.Str(inst.credentialType.name),
                                    "isEnabled" to ConfigValue.Bool(inst.isEnabled),
                                    "customBaseURL" to (inst.customBaseURL?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                    "appendV1Suffix" to ConfigValue.Bool(inst.appendV1Suffix),
                                    "useResponsesAPI" to ConfigValue.Bool(inst.useResponsesAPI),
                                    "azureMode" to ConfigValue.Bool(inst.azureMode),
                                )
                            )
                        }
                    )
                },
            )
        )

        r.register(
            ReadOnlyField(
                path = "models",
                displayName = "Model entries (summary)",
                description = "Read-only list of every model entry (entry_id, display_name, model_id, provider).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val providersById = HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelEntries.map { entry ->
                            val inst = providersById[entry.providerInstanceId]
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "entry_id" to ConfigValue.Str(entry.id),
                                    "display_name" to ConfigValue.Str(entry.model.displayName),
                                    "model_id" to ConfigValue.Str(entry.baseModel.id),
                                    "provider_id" to ConfigValue.Str(entry.providerInstanceId),
                                    "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                    "provider_type" to (inst?.let { ConfigValue.Str(it.providerType.name) } ?: ConfigValue.Null),
                                    "is_custom" to ConfigValue.Bool(entry.isCustom),
                                    "is_hidden" to ConfigValue.Bool(entry.isHidden),
                                )
                            )
                        }
                    )
                },
            )
        )

        r.register(
            ReadOnlyField(
                path = "envvars",
                displayName = "Environment variables (summary)",
                description = "Read-only list of env var keys with note, createdAt and enabled. Values are never exposed.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        envVarRepo.entries.value.map { e ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "key" to ConfigValue.Str(e.key),
                                    "note" to ConfigValue.Str(e.note),
                                    "created_at" to ConfigValue.Str(isoFormatter.format(Date(e.createdAt))),
                                    "enabled" to ConfigValue.Bool(e.enabled),
                                )
                            )
                        }
                    )
                },
            )
        )

        r.register(
            ClosureField(
                path = "envvars.privacyMode",
                displayName = "Privacy Mode",
                description = "When ON, env-var values that appear in shell-execute output are masked before reaching the model. Turning OFF lets the model see raw values — only do this if the user explicitly asked.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = { ConfigValue.Bool(com.openminis.app.data.EnvVarPrivacyStore.isEnabled) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.data.EnvVarPrivacyStore.setEnabled(b)
                },
            )
        )

        r.register(
            ReadOnlyField(
                path = "groups",
                displayName = "Model groups (summary)",
                description = "Read-only list of model groups with entries expanded.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val entriesById =
                        HashMap<String, com.openminis.app.data.model.ModelEntry>(cfg.modelEntries.size)
                    for (e in cfg.modelEntries) entriesById[e.id] = e
                    val providersById =
                        HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelGroups.map { g ->
                            val entries = g.memberEntryIds.map { entryId ->
                                val e = entriesById[entryId]
                                if (e == null) {
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "missing" to ConfigValue.Bool(true),
                                        )
                                    )
                                } else {
                                    val inst = providersById[e.providerInstanceId]
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "display_name" to ConfigValue.Str(e.model.displayName),
                                            "model_id" to ConfigValue.Str(e.baseModel.id),
                                            "provider_id" to ConfigValue.Str(e.providerInstanceId),
                                            "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                        )
                                    )
                                }
                            }
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "strategy" to ConfigValue.Str(g.strategy.name),
                                    "fallback_strategy" to ConfigValue.Str(g.fallbackStrategy.name),
                                    "entries" to ConfigValue.Arr(entries),
                                )
                            )
                        }
                    )
                },
            )
        )
    }


    private fun registerDefaults(r: ConfigRegistry, repo: ProviderRepository) {
        r.register(
            ClosureField(
                path = "defaults.primaryGroup",
                displayName = "Default primary group",
                description = "Group used by new sessions. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.Group)),
                reader = { ConfigValue.Str(repo.defaultPrimaryGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultPrimaryGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultPrimaryGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.subGroup",
                displayName = "Default sub group",
                description = "Secondary group for fallback. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.Group)),
                reader = { ConfigValue.Str(repo.defaultSubGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultSubGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultSubGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopEntries",
                displayName = "Agent loop model entries",
                description = "Model entries available via minis-model-use. Replace with full list to set; use .append/.remove for single-element ops.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.ModelEntry, ordered = false)),
                reader = {
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopModelEntryIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopEntries id: $bad"
                        )
                    }
                    repo.setAgentLoopEntryIds(cleanIds)
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopGroups",
                displayName = "Agent loop groups",
                description = "Whole groups exposed via minis-model-use.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.Group, ordered = false)),
                reader = {
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopGroupIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopGroups id: $bad"
                        )
                    }
                    repo.setAgentLoopGroupIds(cleanIds)
                },
            )
        )
    }


    private fun resolveSoulIconImage(context: Context, raw: String): String {
        val icon = com.openminis.app.agent.SoulIcon
        val bytes: ByteArray = when (val src = icon.classifySource(raw)) {
            is com.openminis.app.agent.SoulIcon.Source.Unsupported ->
                throw ConfigError.InvalidValue(src.reason)

            is com.openminis.app.agent.SoulIcon.Source.Bytes -> src.data

            is com.openminis.app.agent.SoulIcon.Source.LinuxPath -> {
                val root = icon.ALLOWED_LINUX_ROOTS.firstOrNull {
                    src.path == it || src.path.startsWith("$it/")
                } ?: throw ConfigError.InvalidValue(
                    "path must be inside one of ${icon.ALLOWED_LINUX_ROOTS.joinToString(", ")}",
                )
                val sid = ChatViewModelStore.activeSessionId
                    ?: throw ConfigError.InvalidValue(
                        "no active session — open a chat first, or pass the image inline as a data URI",
                    )
                val kernel = com.openminis.app.sandbox.PRootKernel
                val hostRoot = kernel.resolveSessionHostPath(sid, root, context)
                    ?: throw ConfigError.InvalidValue("could not resolve $root on this device")
                val hostFile = kernel.resolveSessionHostPath(sid, src.path, context)
                    ?: throw ConfigError.InvalidValue("could not resolve ${src.path}")
                if (!icon.isContained(hostFile, hostRoot)) {
                    throw ConfigError.InvalidValue("${src.path} resolves outside $root")
                }
                if (!hostFile.isFile) {
                    throw ConfigError.InvalidValue("${src.path} does not exist")
                }
                runCatching { hostFile.readBytes() }.getOrElse {
                    throw ConfigError.InvalidValue("could not read ${src.path}")
                }
            }
        }

        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: throw ConfigError.InvalidValue(
            "that data isn't a decodable image (png / jpeg / webp / gif are supported; svg is not)",
        )

        return when (val r = icon.encode(bitmap)) {
            is com.openminis.app.agent.SoulIcon.EncodeResult.Success -> r.dataUri
            is com.openminis.app.agent.SoulIcon.EncodeResult.Failure -> when (r.reason) {
                com.openminis.app.agent.SoulIcon.Rejection.TOO_LARGE ->
                    throw ConfigError.InvalidValue("that image is too large to store inline")
                com.openminis.app.agent.SoulIcon.Rejection.UNREADABLE ->
                    throw ConfigError.InvalidValue("that image could not be processed")
            }
        }
    }

    private fun registerSoul(r: ConfigRegistry, context: Context) {

        fun loadCurrent(): com.openminis.app.agent.SoulFile =
            com.openminis.app.agent.SoulStore.load(context)
                ?: com.openminis.app.agent.SoulMDParser.parse(
                    com.openminis.app.agent.SoulStore.DEFAULT_CONTENT
                )

        fun saveCurrent(file: com.openminis.app.agent.SoulFile) {
            com.openminis.app.agent.SoulStore.save(context, file)
        }

        r.register(
            ClosureField(
                path = "soul.name",
                displayName = "Soul name",
                description = "The agent's name as shown in chat bubble headers and as the system-prompt identity. Trimmed; empty falls back to \"Minis\".",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text()),
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.name)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val name = raw.trim().ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.name
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(name = name)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.icon",
                displayName = "Soul icon",
                description = "The Soul's identity icon — also called its avatar, its persona image, or its " +
                    "character image (中文：角色形象 / 图标 / 头像). All of those refer to THIS one setting; " +
                    "there is no separate avatar field. Shown beside the assistant name in the chat header " +
                    "and on the Soul settings card.\n" +
                    "\n" +
                    "EMOJI\n" +
                    "  A single emoji, e.g. \"⚡\". An empty string \"\" restores the default sparkle.\n" +
                    "\n" +
                    "IMAGE — any of these forms:\n" +
                    "  • data URI: data:image/png;base64,iVBORw0KGgo...\n" +
                    "  • bare base64 (no data: prefix) — auto-detected\n" +
                    "  • minis:// resource, e.g. minis://attachments/icon.png\n" +
                    "  • a path inside the minis directories, e.g. /var/minis/attachments/icon.png\n" +
                    "  Remote http(s) URLs are NOT supported on Android — download the file first, " +
                    "then pass its path.\n" +
                    "\n" +
                    "TRANSPARENCY — not required. Opaque images (a JPEG, a flattened PNG) are fine; " +
                    "the icon renders with rounded corners, so it still reads as a normal small avatar. " +
                    "Transparency is preserved when the source has it.\n" +
                    "\n" +
                    "PROCESSING — identical to picking an image in Settings → Soul: centre-cropped to a " +
                    "square, downscaled to ${com.openminis.app.agent.SoulIcon.STORED_PIXELS}×" +
                    "${com.openminis.app.agent.SoulIcon.STORED_PIXELS}, re-encoded as PNG and stored inline.\n" +
                    "\n" +
                    "READING — `get soul.icon` returns \"<image>\" for an image, never the base64.\n" +
                    "\n" +
                    "EXAMPLES (the value is JSON, so the string needs its own quotes)\n" +
                    "  minis-config set soul.icon '\"⚡\"'\n" +
                    "  minis-config set soul.icon '\"minis://attachments/icon.png\"'\n" +
                    "  minis-config set soul.icon '\"\"'   # back to the default sparkle",
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = false,
                readable = ConfigReadable(ConfigValueFormatter.Image),
                reader = {
                    val raw = loadCurrent().metadata.icon
                    ConfigValue.Str(
                        if (com.openminis.app.agent.SoulIcon.isDataUri(raw)) "<image>" else raw,
                    )
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val trimmed = raw.trim()
                    val cur = loadCurrent()
                    val next = when {
                        trimmed.isEmpty() -> ""

                        com.openminis.app.agent.SoulIcon.graphemeClusters(trimmed).size == 1 &&
                            com.openminis.app.agent.SoulIcon.isEmojiGlyph(trimmed) -> trimmed

                        else -> resolveSoulIconImage(context, trimmed)
                    }
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(icon = next)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.style",
                displayName = "Soul style",
                description = "Short style descriptor used as a hint in the system prompt (e.g. \"Warm, direct, opinionated\").",
                valueSchema = ConfigSchema.Str(maxLength = 256),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.style)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val style = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(style = style)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.lang",
                displayName = "Soul language",
                description = "Preferred response language: auto / zh / en. Older SOUL.md files missing the field are upgraded on first write.",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(
                    ConfigValueFormatter.EnumLabels(mapOf("auto" to "Automatic", "zh" to "Chinese", "en" to "English")),
                    label = "Soul language preference",
                ),
                reader = {
                    val cur = loadCurrent()
                    ConfigValue.Str(cur.metadata.lang.ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.lang
                    })
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val lang = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(lang = lang)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.body",
                displayName = "Soul personality prompt",
                description = "Personality / voice instructions injected as a block in the system prompt. Length cap is language-aware: Chinese ≤ ${com.openminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT} chars OR English ≤ ${com.openminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT} words (rule picked by the majority language). The writer also rejects prompt-injection patterns (\"ignore previous instructions\" etc.) — keep this to genuine character / tone guidance.",
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.LongText, label = "Soul personality body"),
                reader = {
                    ConfigValue.Str(loadCurrent().body)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    if (com.openminis.app.agent.SystemPromptBuilder
                            .containsInjectionPattern(raw)
                    ) {
                        throw ConfigError.InvalidValue(
                            "Body contains a prompt-injection pattern (\"ignore/disregard/forget … previous/prior instructions\"). SOUL.md is for personality, not instructions to the model."
                        )
                    }
                    val check = com.openminis.app.agent.SoulStore.isOverLimit(raw)
                    when (check) {
                        is com.openminis.app.agent.SoulBodyLimitCheck.Ok -> Unit
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitChinese ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.chars} Chinese chars exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} chars, or rewrite mostly in English to use the ≤ ${com.openminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT}-word cap."
                            )
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitEnglish ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.words} English words exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} words, or rewrite mostly in Chinese to use the ≤ ${com.openminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT}-char cap."
                            )
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(body = raw))
                },
            )
        )
    }
}
