package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.ImagePreview
import com.openminis.app.ui.sandbox.TextPreview
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SkillRowItem
import com.openminis.app.ui.settings.SkillSourceUrl
import com.openminis.app.ui.components.MinisTextButton

@Composable
fun SessionSkillsSheet(
    sessionId: String,
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()

    val overrides = remember(skills) {
        mutableStateMapOf<String, Boolean>().apply {
            for (skill in skills) {
                put(skill.id, skillRepository.isEnabledForSession(skill.id, sessionId))
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val filteredSkills by remember(skills) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) skills
            else skills.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }
    }

    val listScroll = rememberScrollState()
    var page by remember { mutableStateOf<SessionSkillsPage>(SessionSkillsPage.List) }

    val pageSkill = page.skillId?.let { id -> skills.find { it.id == id } }
    val pageSkillGone = page.skillId != null && pageSkill == null
    LaunchedEffect(pageSkillGone) {
        if (pageSkillGone) page = SessionSkillsPage.List
    }

    val currentPage = page
    StandardChatSheet(
        title = when (currentPage) {
            SessionSkillsPage.List -> stringResource(R.string.session_skills_title)
            is SessionSkillsPage.Detail -> pageSkill?.name.orEmpty()
            is SessionSkillsPage.File -> currentPage.relativePath.substringAfterLast('/')
        },
        onDismiss = onDismiss,
        onBack = {
            if (page == SessionSkillsPage.List) false else {
                page = page.back()
                true
            }
        },
        leadingAction = if (currentPage == SessionSkillsPage.List) null else {
            {
                IconButton(onClick = { page = currentPage.back() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            }
        },
    ) {
        when (currentPage) {
            is SessionSkillsPage.Detail -> {
                if (pageSkill != null) {
                    SessionSkillDetail(
                        skill = pageSkill,
                        skillRepository = skillRepository,
                        onFileClick = { path -> page = SessionSkillsPage.File(pageSkill.id, path) },
                    )
                }
                return@StandardChatSheet
            }
            is SessionSkillsPage.File -> {
                SessionSkillFilePreview(skillRepository, currentPage.id, currentPage.relativePath)
                return@StandardChatSheet
            }
            SessionSkillsPage.List -> Unit
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listScroll),
        ) {
            if (skills.isEmpty()) {
                EmptySkillsCard()
            } else {
                DialogTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.skills_search_placeholder),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(start = 32.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_skills_header_installed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(
                        onClick = {
                            for (skill in filteredSkills) {
                                overrides[skill.id] = true
                                skillRepository.setSessionOverride(sessionId, skill.id, true)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_enable_all), fontSize = 12.sp)
                    }
                    MinisTextButton(
                        onClick = {
                            for (skill in filteredSkills) {
                                overrides[skill.id] = false
                                skillRepository.setSessionOverride(sessionId, skill.id, false)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.session_skills_disable_all), fontSize = 12.sp)
                    }
                }

                if (filteredSkills.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_search_no_match, searchQuery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                } else {
                    SettingsSection(
                        footer = stringResource(R.string.session_skills_footer),
                    ) {
                        filteredSkills.forEachIndexed { index, skill ->
                            val sourceUrl = SkillSourceUrl.copyable(skill)
                            var menuExpanded by remember(skill.id) { mutableStateOf(false) }
                            Box {
                                SkillRowItem(
                                    name = skill.name,
                                    description = skill.description,
                                    importSource = skill.importSource,
                                    isEnabled = overrides[skill.id] ?: skill.isEnabled,
                                    onToggle = { enabled ->
                                        overrides[skill.id] = enabled
                                        skillRepository.setSessionOverride(sessionId, skill.id, enabled)
                                    },
                                    onClick = { page = SessionSkillsPage.Detail(skill.id) },
                                    showDivider = index < filteredSkills.size - 1,
                                    onLongClick = sourceUrl?.let {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuExpanded = true
                                        }
                                    },
                                )
                                if (sourceUrl != null) {
                                    MinisMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.skill_copy_source_url)) },
                                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                SkillSourceUrl.copy(context, sourceUrl)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun EmptySkillsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Text(
                stringResource(R.string.session_skills_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.session_skills_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

internal sealed interface SessionSkillsPage {
    data object List : SessionSkillsPage
    data class Detail(val id: String) : SessionSkillsPage
    data class File(val id: String, val relativePath: String) : SessionSkillsPage

    val skillId: String?
        get() = when (this) {
            List -> null
            is Detail -> id
            is File -> id
        }

    fun back(): SessionSkillsPage = when (this) {
        List, is Detail -> List
        is File -> Detail(id)
    }
}

internal fun sessionSkillOtherFiles(all: List<String>): List<String> =
    all.filterNot { it.equals("SKILL.md", ignoreCase = true) }

@Composable
private fun SessionSkillDetail(
    skill: SkillRepository.Skill,
    skillRepository: SkillRepository,
    onFileClick: (String) -> Unit,
) {
    val skillMd = remember(skill.id, skill.updatedAt) {
        skillRepository.readSkillFile(skill.id, "SKILL.md") ?: skill.body
    }
    val otherFiles = remember(skill.id, skill.updatedAt) {
        sessionSkillOtherFiles(skillRepository.listSkillFiles(skill.id))
            .map { it to skillFileItem(skillRepository, skill.id, it).isImageFile }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSection(header = "SKILL.md") {
            SelectionContainer {
                Text(
                    text = skillMd,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                )
            }
        }
        if (otherFiles.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.session_skill_other_files)) {
                otherFiles.forEachIndexed { index, (path, isImage) ->
                    SettingsRow(
                        title = path,
                        icon = if (isImage) {
                            Icons.Outlined.Image
                        } else {
                            Icons.Outlined.Description
                        },
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                        onClick = { onFileClick(path) },
                        showDivider = index < otherFiles.size - 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun SessionSkillFilePreview(
    skillRepository: SkillRepository,
    skillId: String,
    relativePath: String,
) {
    val item = remember(skillId, relativePath) { skillFileItem(skillRepository, skillId, relativePath) }
    if (item.isImageFile) ImagePreview(item) else TextPreview(item)
}

private fun skillFileItem(skillRepository: SkillRepository, skillId: String, relativePath: String): FileItem {
    val file = java.io.File(skillRepository.skillFileHostPath(skillId, relativePath))
    return FileItem(
        file = file,
        name = file.name,
        isDirectory = false,
        isSymlink = false,
        size = file.length(),
        modifiedMs = file.lastModified(),
    )
}
