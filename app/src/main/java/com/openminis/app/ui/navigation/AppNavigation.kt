package com.openminis.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.ui.settings.KEY_LAUNCH_SESSION
import com.openminis.app.ui.settings.getAppearancePrefs
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.chat.ChatScreen
import com.openminis.app.ui.sessions.SessionListScreen
import com.openminis.app.ui.settings.AboutScreen
import com.openminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.openminis.app.ui.settings.AddAgentLoopModelsScreen
import com.openminis.app.ui.settings.AddCustomModelScreen
import com.openminis.app.ui.settings.BackgroundSettingsScreen
import com.openminis.app.ui.settings.AddModelsToGroupScreen
import com.openminis.app.ui.settings.ShadowVoiceDetailScreen
import com.openminis.app.ui.settings.AddProviderScreen
import com.openminis.app.ui.settings.ModelEntryDetailScreen
import com.openminis.app.ui.settings.ModelGroupDetailScreen
import com.openminis.app.ui.settings.ModelGroupsScreen
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.ProviderListScreen
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.FilePreviewScreen
import com.openminis.app.ui.sandbox.RootfsManagementScreen
import com.openminis.app.ui.settings.EnvironmentVariablesScreen
import com.openminis.app.ui.settings.AppearanceScreen
import com.openminis.app.ui.settings.SettingsScreen
import com.openminis.app.ui.settings.SystemPermissionsScreen
import com.openminis.app.ui.settings.SessionStorageDetailScreen
import com.openminis.app.ui.settings.SkillDetailScreen
import com.openminis.app.ui.settings.StorageManagementScreen
import com.openminis.app.ui.settings.SkillFileViewerScreen
import com.openminis.app.ui.settings.UsageStatsScreen
import com.openminis.app.ui.settings.MinisSkillsBrowserScreen
import com.openminis.app.ui.settings.MountDetailScreen
import com.openminis.app.ui.settings.MountedFoldersScreen
import com.openminis.app.ui.settings.SharedFolderDetailScreen
import com.openminis.app.ui.settings.SharedFoldersScreen
import com.openminis.app.ui.settings.SkillsManagementScreen
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.OffloadPermissionScreen
import com.openminis.app.ui.settings.ShizukuPermissionScreen
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.terminal.TerminalScreen
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen
import kotlinx.coroutines.flow.first

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    const val SHADOW_VOICE_DETAIL = "voice_service/{instanceId}"
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val BUILT_IN_MODELS = "built_in_models"
    const val ADD_PROVIDER_TYPE = "add_provider_type/{providerType}"
    fun addProviderType(type: String) = "add_provider_type/$type"
    const val BUILT_IN_PLATFORM = "built_in_platform/{platformId}"
    fun builtInPlatform(id: String) = "built_in_platform/$id"
    const val BACKUP = "backup"
    const val BACKUP_INITIAL_TAB_KEY = "backup_initial_tab"
    const val BACKUP_DESTINATIONS = "backup_destinations"
    const val BACKUP_HISTORY_DETAIL = "backup_history_detail"
    const val BACKUP_DESTINATION_BROWSE = "backup_destination_browse"
    const val RESTORE_BROWSE = "restore_browse"
    const val RESTORE_SERVERS = "restore_servers"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryKey}"
    fun mirrorCategoryDetail(categoryKey: String) = "mirror_category/$categoryKey"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val ENV_VARS = "env_vars"
    const val NETWORK = "network"
    const val NETWORK_TAILSCALE = "network_tailscale"
    const val NETWORK_PROXIES = "network_proxies"
    const val CONTAINER_DNS = "container_dns"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }
    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            if (initCommand != null) add("initCommand=${enc(initCommand)}")
            if (sessionId != null) add("sessionId=${enc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }
    const val CHAT_FILES = "chat_files/{sessionId}?path={path}"
    fun chatFiles(sessionId: String, path: String? = null) =
        if (path == null) "chat_files/$sessionId" else "chat_files/$sessionId?path=${android.net.Uri.encode(path)}"
    const val AGENT_TRANSCRIPT = "agent_transcript/{sessionId}"
    fun agentTranscript(sessionId: String) = "agent_transcript/$sessionId"
    const val MEMORY = "memory"
    const val AGENTS = "agents"
    const val SUB_AGENTS = "sub_agents"
    const val SUB_AGENT_DETAIL = "sub_agent/{agentId}"
    const val AGENT_TOOLS = "agent_tools"
    const val MCP = "mcp"
    const val SOUL = "soul"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    const val SHIZUKU = "shizuku"
    const val SYSTEM_PERMISSIONS = "system_permissions"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val CONFIG_AUDIT_DETAIL = "config_audit_detail/{auditId}"
    const val APPEARANCE = "appearance"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}"
    fun scheduledTaskEdit(taskId: String? = null): String =
        if (taskId == null) "scheduled_tasks/edit" else "scheduled_tasks/edit?taskId=$taskId"
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"
    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun configAuditDetail(auditId: String) = "config_audit_detail/$auditId"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun shadowVoiceDetail(instanceId: String) = "voice_service/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var currentSessionId: String? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
) {
    val context = LocalContext.current

    val mountedFoldersStore = remember {
        (context.applicationContext as com.openminis.app.MinisApp).mountedFoldersStore
    }

    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenTerminal -> {
                navController.safeNavigate(Routes.terminal(initialDeepLink.initCommand))
            }
            is DeepLinkAction.OpenSession -> {
                navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            is DeepLinkAction.CreateEnvironmentVariable -> {
                DeepLinkCoordinator.setPendingEnvVarCreate(
                    initialDeepLink.key,
                    initialDeepLink.value,
                    initialDeepLink.note,
                )
                navController.safeNavigate(Routes.ENV_VARS)
            }
            is DeepLinkAction.OpenSettingsScreen -> {
                navController.safeNavigate(initialDeepLink.route)
            }
            is DeepLinkAction.OpenPermissionSettings -> {
                navController.safeNavigate(Routes.PERMISSIONS)
            }
            is DeepLinkAction.OpenHtmlPreview -> {}
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewVoiceChat,
            is DeepLinkAction.NewCameraChat -> {
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect
        val hasPendingShare =
            com.openminis.app.share.ShareCoordinator.bufferVersion.value > 0
        if (hasPendingShare) {
            val draft = newDraftSessionId()
            if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {
                navController.navigate(Routes.chat(draft)) {
                    popUpTo(Routes.SESSION_LIST) { inclusive = false }
                }
            }
            return@LaunchedEffect
        }
        val rawMode = getAppearancePrefs(context).getInt(KEY_LAUNCH_SESSION, 0)
        val mode = if (
            com.openminis.app.diagnostics.HangDetector.shouldForceHomeOnLaunch(context) ||
            com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(context) ||
            com.openminis.app.diagnostics.LaunchCycleBeacon.shouldForceHomeOnLaunch()
        ) 3 else rawMode
        val autoThresholdMs = 15L * 60 * 1000
        val hasAnySession = chatRepository.dao.listSessions().isNotEmpty()
        kotlinx.coroutines.withTimeoutOrNull(2_000) { providerRepository.awaitConfigLoaded() }
        val hasAnyProvider = providerRepository.instances.isNotEmpty()
        com.openminis.app.logging.AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] launch/mode: rawMode=$rawMode effectiveMode=$mode " +
                "hasPendingShare=$hasPendingShare hasAnySession=$hasAnySession " +
                "hasAnyProvider=$hasAnyProvider",
        )
        val target: String? = when {
            !hasAnySession && !hasAnyProvider -> null
            mode == 1 -> chatRepository.dao.listSessions().firstOrNull { !it.isChild }?.let { Routes.chat(it.id) }
            mode == 2 -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
            mode == 3 -> null
            else -> {
                val latest = chatRepository.dao.listSessions().firstOrNull { !it.isChild }
                    ?: return@LaunchedEffect
                val fresh = System.currentTimeMillis() - latest.updatedAt < autoThresholdMs
                run {
                    val now = System.currentTimeMillis()
                    com.openminis.app.logging.AppLogger.info(
                        "XSessionDiag",
                        "[XSessionDiag] launch/auto: candidate=${latest.id.take(8)} " +
                            "title=${latest.title?.take(24)} " +
                            "updatedAt=${latest.updatedAt} now=$now " +
                            "ageMs=${now - latest.updatedAt} thresholdMs=$autoThresholdMs " +
                            "fresh=$fresh -> ${if (fresh) "RESUME_EXISTING" else "NEW_DRAFT"}",
                    )
                }
                if (fresh) Routes.chat(latest.id) else Routes.chat("__new__${java.util.UUID.randomUUID()}")
            }
        }
        if (target != null) {
            navController.navigate(target) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        if (!com.openminis.app.share.ShareCoordinator.hasUnaddressedShare()) return@LaunchedEffect
        val entry = navController.currentBackStackEntry ?: return@LaunchedEffect
        val route = entry.destination.route
        if (route == Routes.SESSION_LIST || route == Routes.CHAT) return@LaunchedEffect
        entry.lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        val draft = newDraftSessionId()
        if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {
            navController.navigate(Routes.chat(draft)) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
    val quickActionStart: String? = when (initialDeepLink) {
        is DeepLinkAction.NewVoiceChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.START_VOICE,
            )
            Routes.chat("__new__${java.util.UUID.randomUUID()}")
        }
        is DeepLinkAction.NewCameraChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
            )
            Routes.chat("__new__${java.util.UUID.randomUUID()}")
        }
        is DeepLinkAction.NewChat -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
        else -> null
    }
    val startDestination = when {
        htmlShortcut != null -> {
            DeepLinkCoordinator.setPendingHtmlPreview(
                htmlShortcut.sessionId,
                htmlShortcut.resourcePath,
                htmlShortcut.title,
            )
            Routes.chat(htmlShortcut.sessionId)
        }
        quickActionStart != null -> quickActionStart
        else -> Routes.SESSION_LIST
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
    ) {
        composable(Routes.SESSION_LIST) {
            ChatSplitScaffoldRoute(
                initialSessionId = null,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ChatSplitScaffoldRoute(
                initialSessionId = sessionId,
                navController = navController,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.safePopBackStack() },
                onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onRootfsClick = { navController.safeNavigate(Routes.STORAGE) },
                onBackupClick = { navController.safeNavigate(Routes.BACKUP) },
                onEnvVarsClick = { navController.safeNavigate(Routes.ENV_VARS) },
                onNetworkClick = { navController.safeNavigate(Routes.NETWORK) },
                onTailscaleClick = { navController.safeNavigate(Routes.NETWORK_TAILSCALE) },
                onProxiesClick = { navController.safeNavigate(Routes.NETWORK_PROXIES) },
                onContainerDnsClick = { navController.safeNavigate(Routes.CONTAINER_DNS) },
                onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
                onTerminalClick = { navController.safeNavigate(Routes.terminal()) },
                onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
                onAgentsClick = { navController.safeNavigate(Routes.AGENTS) },
                onAgentToolsClick = { navController.safeNavigate(Routes.AGENT_TOOLS) },
                onMcpClick = { navController.safeNavigate(Routes.MCP) },
                onSoulClick = { navController.safeNavigate(Routes.SOUL) },
                onPermissionsClick = { navController.safeNavigate(Routes.PERMISSIONS) },
                onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
                onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
                onLogsClick = { navController.safeNavigate(Routes.LOGS) },
                onAboutClick = { navController.safeNavigate(Routes.ABOUT) },
                onMountedFoldersClick = { navController.safeNavigate(Routes.MOUNTED_FOLDERS) },
                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
                onBuiltInModelsClick = { navController.safeNavigate(Routes.BUILT_IN_MODELS) },
            )
        }

        composable(Routes.BUILT_IN_MODELS) {
            com.openminis.app.ui.settings.BuiltInModelsScreen(
                onBack = { navController.safePopBackStack() },
                onOpenPlatform = { id -> navController.safeNavigate(Routes.builtInPlatform(id)) },
                onOpenProviderType = { type ->
                    navController.safeNavigate(Routes.addProviderType(type.name))
                },
            )
        }

        composable(
            Routes.BUILT_IN_PLATFORM,
            arguments = listOf(navArgument("platformId") { type = NavType.StringType }),
        ) { entry ->
            val platformId = entry.arguments?.getString("platformId").orEmpty()
            when (platformId) {
                "workbuddy" -> com.openminis.app.ui.settings.WorkBuddyPlatformScreen(
                    providerRepository = providerRepository,
                    onBack = { navController.safePopBackStack() },
                )
                "qoder", "minimax" -> com.openminis.app.ui.settings.BuiltInAccountScreen(
                    platform = platformId,
                    providerRepository = providerRepository,
                    onBack = { navController.safePopBackStack() },
                )
                else -> {}
            }
        }

        composable(Routes.BACKUP) { entry ->
            val initialTab = remember(entry) {
                navController.previousBackStackEntry?.savedStateHandle
                    ?.remove<Int>(Routes.BACKUP_INITIAL_TAB_KEY) ?: 0
            }
            com.openminis.app.ui.settings.backup.BackupAndRestoreScreen(
                initialTab = initialTab,
                onBack = { navController.safePopBackStack() },
                onManageDestinations = { navController.safeNavigate(Routes.BACKUP_DESTINATIONS) },
                onChooseRestoreServer = { navController.safeNavigate(Routes.RESTORE_SERVERS) },
                onOpenHistoryRecord = { id ->
                    navController.safeNavigate("${Routes.BACKUP_HISTORY_DETAIL}/$id")
                },
                onBrowseDestination = { name ->
                    navController.safeNavigate(
                        "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                    )
                },
            )
        }

        composable(
            "${Routes.BACKUP_HISTORY_DETAIL}/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("recordId").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val history = remember { com.openminis.app.backup.BackupHistory.get(ctx) }
            val record = remember(id) { history.records().firstOrNull { it.id == id } }
            if (record == null) {
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                com.openminis.app.ui.settings.backup.BackupHistoryDetailScreen(
                    record = record,
                    onBack = { navController.safePopBackStack() },
                    onRemove = {
                        history.remove(id)
                        navController.safePopBackStack()
                    },
                    onRemoveWithFiles = { vm.removeHistoryRecordWithFiles(id) },
                    onRemoved = { navController.safePopBackStack() },
                    onOpenDestination = { name ->
                        navController.safeNavigate(
                            "${Routes.BACKUP_DESTINATION_BROWSE}/" +
                                android.net.Uri.encode(name),
                        )
                    },
                )
            }
        }

        composable(
            "${Routes.BACKUP_DESTINATION_BROWSE}/{remoteName}",
            arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("remoteName").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val remote = remember(name) {
                com.openminis.app.backup.remote.RcloneRemoteStore(ctx).remote(name)
            }
            if (remote == null) {
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                com.openminis.app.ui.settings.backup.BackupDestinationBrowseScreen(
                    remote = remote,
                    vm = vm,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.RESTORE_SERVERS) {
            com.openminis.app.ui.settings.backup.RestoreServersScreen(
                onBack = { navController.safePopBackStack() },
                onPickServer = { name ->
                    navController.safeNavigate(
                        "${Routes.RESTORE_BROWSE}/" + android.net.Uri.encode(name),
                    )
                },
            )
        }

        composable(
            "${Routes.RESTORE_BROWSE}/{remoteName}",
            arguments = listOf(navArgument("remoteName") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("remoteName").orEmpty()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val remote = remember(name) {
                com.openminis.app.backup.remote.RcloneRemoteStore(ctx).remote(name)
            }
            if (remote == null) {
                LaunchedEffect(Unit) { navController.safePopBackStack() }
            } else {
                val parent = remember(entry) { navController.getBackStackEntry(Routes.BACKUP) }
                val vm: com.openminis.app.ui.settings.backup.BackupViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(parent)
                com.openminis.app.ui.settings.backup.RestoreBrowseScreen(
                    remote = remote,
                    vm = vm,
                    onBack = { navController.safePopBackStack() },
                    onPicked = {
                        navController.safePopBackStack(
                            route = Routes.BACKUP,
                            inclusive = false,
                        )
                    },
                )
            }
        }

        composable(Routes.BACKUP_DESTINATIONS) {
            com.openminis.app.ui.settings.backup.RcloneDestinationsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SHARED_FOLDERS) {
            SharedFoldersScreen(
                onBack = { navController.safePopBackStack() },
                onFolderClick = { folderId ->
                    navController.safeNavigate(Routes.sharedFoldersDetail(folderId))
                },
            )
        }

        composable(
            route = Routes.SHARED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SharedFolderDetailScreen(
                folderId = folderId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(ctx.applicationContext)
                    val hostPath = java.io.File(rootfs.rootfsDir, "var/minis/$folderId")
                    val label = when (folderId) {
                        "shared" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_shared)
                        "skills" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_skills)
                        "memory" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_memory)
                        else -> folderId
                    }
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = hostPath,
                        rootLabel = label,
                        linuxRootPath = "/var/minis/$folderId",
                        appContext = ctx.applicationContext,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.MOUNTED_FOLDERS) {
            MountedFoldersScreen(
                store = mountedFoldersStore,
                onBack = { navController.safePopBackStack() },
                onMountClick = { mountId ->
                    navController.safeNavigate(Routes.mountedFoldersDetail(mountId))
                },
            )
        }

        composable(
            route = Routes.MOUNTED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("mountId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val mountId = backStackEntry.arguments?.getString("mountId") ?: return@composable
            val context = androidx.compose.ui.platform.LocalContext.current
            MountDetailScreen(
                store = mountedFoldersStore,
                mountId = mountId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val entry = mountedFoldersStore.entries.value.firstOrNull { it.id == mountId }
                    val hostPath = entry?.resolvedHostPath
                    if (hostPath != null) {
                        FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                            rootPath = java.io.File(hostPath),
                            rootLabel = entry.name,
                        )
                        navController.safeNavigate(Routes.FILE_BROWSER)
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Mount path unavailable",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        composable(Routes.PROVIDER_LIST) {
            ProviderListScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
                onProviderClick = { instanceId ->
                    navController.safeNavigate(Routes.providerDetail(instanceId))
                },
                onVoiceServiceClick = { instanceId ->
                    navController.safeNavigate(Routes.shadowVoiceDetail(instanceId))
                },
            )
        }

        composable(
            route = Routes.SHADOW_VOICE_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ShadowVoiceDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ADD_PROVIDER) {
            AddProviderScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.ADD_PROVIDER_TYPE,
            arguments = listOf(navArgument("providerType") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("providerType").orEmpty()
            val preset = com.openminis.app.data.model.ProviderType.decoded(raw)
            AddProviderScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
                initialType = preset.takeIf {
                    it != com.openminis.app.data.model.ProviderType.unsupported
                },
            )
        }

        composable(
            route = Routes.PROVIDER_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ProviderDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onModelEntryClick = { entryId ->
                    navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
                },
                onAddCustomModel = {
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
                onVoiceServiceClick = { id ->
                    navController.safeNavigate(Routes.shadowVoiceDetail(id))
                },
            )
        }

        composable(Routes.MODEL_GROUPS) {
            ModelGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onGroupClick = { groupId ->
                    navController.safeNavigate(Routes.modelGroupDetail(groupId))
                },
                onAddAgentLoopModels = {
                    navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP)
                },
                onAddAgentLoopGroups = {
                    navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP)
                },
            )
        }

        composable(
            route = Routes.MODEL_GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            ModelGroupDetailScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddModels = {
                    navController.safeNavigate(Routes.addModelsToGroup(groupId))
                },
            )
        }

        composable(
            route = Routes.ADD_MODELS_TO_GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            AddModelsToGroupScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
            AddAgentLoopModelsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
            AddAgentLoopGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.AGENT_LOOP_MODELS) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navController.safePopBackStack()
            }
        }

        composable(
            route = Routes.MODEL_ENTRY_DETAIL,
            arguments = listOf(
                navArgument("instanceId") { type = NavType.StringType },
                navArgument("entryId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            val entryId = backStackEntry.arguments?.getString("entryId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            ModelEntryDetailScreen(
                instanceId = instanceId,
                entryId = entryId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.ADD_CUSTOM_MODEL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            AddCustomModelScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.STORAGE) {
            val storageContext = androidx.compose.ui.platform.LocalContext.current
            val storageVm: com.openminis.app.ui.settings.StorageUsageViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = com.openminis.app.ui.settings.StorageUsageViewModel.factory(storageContext, chatRepository),
                )
            StorageManagementScreen(
                vm = storageVm,
                onBack = { navController.safePopBackStack() },
                onRootfsClick = { navController.safeNavigate(Routes.ROOTFS_MANAGEMENT) },
                onSessionClick = { sessionId ->
                    navController.safeNavigate(Routes.sessionStorageDetail(sessionId))
                },
            )
        }

        composable(
            route = Routes.SESSION_STORAGE_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val storageVm = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Routes.STORAGE) }.getOrNull()
            }?.let { parent ->
                androidx.lifecycle.viewmodel.compose.viewModel<com.openminis.app.ui.settings.StorageUsageViewModel>(
                    viewModelStoreOwner = parent,
                    factory = com.openminis.app.ui.settings.StorageUsageViewModel.factory(
                        androidx.compose.ui.platform.LocalContext.current, chatRepository,
                    ),
                )
            }
            SessionStorageDetailScreen(
                sessionId = sessionId,
                chatDao = chatRepository.dao,
                onBack = { navController.safePopBackStack() },
                onMeasured = { id, minis, media -> storageVm?.onSessionMeasured(id, minis, media) },
                onBrowseFiles = { rootPath ->
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = java.io.File(rootPath),
                        rootLabel = "Session Files",
                        displayLinuxPrefix = "/var/minis",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.ROOTFS_MANAGEMENT) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RootfsManagementScreen(
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val rootfs = RootfsManager.getInstance(context.applicationContext)
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = rootfs.rootfsDir,
                        rootLabel = "/",
                        displayLinuxPrefix = "/",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
                onMirrorCategoryClick = { category ->
                    navController.safeNavigate(Routes.mirrorCategoryDetail(category.key))
                },
            )
        }

        composable(
            route = Routes.MIRROR_CATEGORY_DETAIL,
            arguments = listOf(navArgument("categoryKey") { type = NavType.StringType }),
        ) { backStackEntry ->
            val categoryKey = backStackEntry.arguments?.getString("categoryKey") ?: return@composable
            val category = com.openminis.app.ui.sandbox.MirrorCatalog.categoryFromKey(categoryKey)
                ?: return@composable
            com.openminis.app.ui.sandbox.MirrorCategoryDetailScreen(
                category = category,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.FILE_BROWSER) {
            val vm = FilePreviewHolder.fileBrowserViewModel ?: return@composable
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    FilePreviewHolder.currentSessionId = null
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        composable(
            route = Routes.CHAT_FILES,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val rootfs = RootfsManager.getInstance(context.applicationContext)
            val varMinis = java.io.File(rootfs.rootfsDir, "var/minis")
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val folderPath = backStackEntry.arguments?.getString("path")
                ?.trim('/')?.takeIf { it.isNotEmpty() }
            val vm = remember(rootfs.rootfsDir.absolutePath, varMinis.absolutePath, sessionId, folderPath) {
                FileBrowserViewModel(
                    rootPath = rootfs.rootfsDir,
                    initialPath = folderPath?.let { java.io.File(rootfs.rootfsDir, it) }
                        ?: varMinis.takeIf { it.exists() },
                    rootLabel = "/",
                    linuxRootPath = "/",
                    sessionId = sessionId,
                    appContext = context.applicationContext,
                )
            }
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    FilePreviewHolder.currentSessionId = sessionId
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        dialog(
            route = Routes.FILE_PREVIEW,
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val item = FilePreviewHolder.currentItem ?: return@dialog
            FilePreviewScreen(
                item = item,
                sessionId = FilePreviewHolder.currentSessionId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.NETWORK) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                providers = providerRepository.instances.map { com.openminis.app.ui.settings.NetworkProviderRow(it.id, it.label) },
            )
        }
        composable(Routes.NETWORK_TAILSCALE) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                page = com.openminis.app.ui.settings.NetworkPage.TAILSCALE,
            )
        }
        composable(Routes.NETWORK_PROXIES) {
            com.openminis.app.ui.settings.NetworkSettingsScreen(
                onBack = { navController.safePopBackStack() },
                page = com.openminis.app.ui.settings.NetworkPage.PROXIES,
            )
        }
        composable(Routes.CONTAINER_DNS) {
            com.openminis.app.ui.sandbox.ContainerDnsScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.ENV_VARS) {
            if (envVarRepository != null) {
                EnvironmentVariablesScreen(
                    envVarRepository = envVarRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.SKILLS) {
            if (skillRepository != null) {
                SkillsManagementScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                    onMinisSkillsClick = { navController.safeNavigate(Routes.MINIS_SKILLS_BROWSER) },
                )
            }
        }

        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            if (skillRepository != null) {
                SkillDetailScreen(
                    skillId = skillId,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { id, relativePath ->
                        navController.safeNavigate(Routes.skillFile(id, relativePath))
                    },
                )
            }
        }

        composable(
            route = Routes.SKILL_FILE,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("relativePath") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            val rawPath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
            val relativePath = java.net.URLDecoder.decode(rawPath, "UTF-8")
            if (skillRepository != null) {
                SkillFileViewerScreen(
                    skillId = skillId,
                    relativePath = relativePath,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MINIS_SKILLS_BROWSER) {
            if (skillRepository != null) {
                MinisSkillsBrowserScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(
            Routes.TERMINAL,
            arguments = listOf(
                androidx.navigation.navArgument("initCommand") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                androidx.navigation.navArgument("sessionId") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val context = androidx.compose.ui.platform.LocalContext.current
            val initCommand = backStackEntry.arguments?.getString("initCommand")
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val session = remember { TerminalSession(context.applicationContext) }
            TerminalScreen(
                terminalSession = session,
                onBack = { navController.safePopBackStack() },
                initCommand = initCommand,
                sessionId = sessionId,
            )
        }

        composable(Routes.MEMORY) {
            if (memoryRepository != null) {
                MemoryManagementScreen(
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { fileName, isGlobal ->
                        navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                    },
                )
            }
        }

        composable(Routes.MCP) {
            if (mcpRepository != null) {
                com.openminis.app.ui.settings.MCPIntegrationsScreen(
                    mcpRepository = mcpRepository,
                    onBack = { navController.safePopBackStack() },
                    envVarRepository = envVarRepository,
                )
            }
        }

        composable(Routes.SOUL) {
            com.openminis.app.ui.settings.SoulSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.MEMORY_FILE_EDIT,
            arguments = listOf(
                navArgument("fileName") { type = NavType.StringType },
                navArgument("isGlobal") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
            if (memoryRepository != null) {
                MemoryFileEditScreen(
                    fileName = fileName,
                    isGlobal = isGlobal,
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.PERMISSIONS) {
            OffloadPermissionScreen(
                onBack = { navController.safePopBackStack() },
                onOpenPrivilegedBackend = { navController.safeNavigate(Routes.SHIZUKU) },
            )
        }

        composable(Routes.SHIZUKU) {
            ShizukuPermissionScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SYSTEM_PERMISSIONS) {
            SystemPermissionsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.USAGE_STATS) {
            UsageStatsScreen(
                chatDao = chatRepository.dao,
                providerConfig = providerRepository.config.value,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.BACKGROUND) {
            BackgroundSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.AGENTS) {
            com.openminis.app.ui.settings.SubAgentsScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.navigate("sub_agent/$id") },
            )
        }

        composable(Routes.SUB_AGENTS) {
            com.openminis.app.ui.settings.SubAgentsScreen(
                onBack = { navController.safePopBackStack() },
                onOpen = { id -> navController.navigate("sub_agent/$id") },
            )
        }

        composable(
            Routes.SUB_AGENT_DETAIL,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.ui.settings.SubAgentDetailScreen(
                agentId = entry.arguments?.getString("agentId").orEmpty(),
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.AGENT_TOOLS) {
            com.openminis.app.ui.settings.AgentToolsSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.AGENT_TRANSCRIPT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            com.openminis.app.ui.chat.AgentTranscriptScreen(
                sessionId = sessionId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.LOGS) {
            LogManagementScreen(
                onBack = { navController.safePopBackStack() },
                onLogFileClick = { fileName ->
                    navController.safeNavigate(Routes.logDetail(fileName))
                },
                onConfigAuditEntryClick = { auditId ->
                    navController.safeNavigate(Routes.configAuditDetail(auditId))
                },
            )
        }

        composable(
            route = Routes.CONFIG_AUDIT_DETAIL,
            arguments = listOf(navArgument("auditId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val auditId = backStackEntry.arguments?.getString("auditId") ?: return@composable
            com.openminis.app.ui.settings.ConfigAuditDetailScreen(
                entryId = auditId,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.LOG_DETAIL,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            LogDetailScreen(
                fileName = fileName,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ONBOARDING_MODELS) {
            OnboardingModelSelectionScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddCustomModel = { instanceId ->
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
            )
        }

        composable(Routes.SCHEDULED_TASKS) {
            com.openminis.app.ui.scheduled.ScheduledTasksScreen(
                onBack = { navController.safePopBackStack() },
                onEditTask = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskEdit(taskId))
                },
                onViewRuns = { taskId ->
                    navController.safeNavigate(Routes.scheduledTaskRuns(taskId))
                },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        composable(
            route = Routes.SCHEDULED_TASK_RUNS,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
            com.openminis.app.ui.scheduled.ScheduledTaskRunsScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
        composable(
            route = Routes.SCHEDULED_TASK_EDIT,
            arguments = listOf(
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")
            com.openminis.app.ui.scheduled.ScheduledTaskEditScreen(
                taskId = taskId,
                onBack = { navController.safePopBackStack() },
                onOpenSession = { sessionId ->
                    navController.safeNavigate(Routes.chat(sessionId))
                },
            )
        }
    }
}
