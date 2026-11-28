package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.learning.LessonStore
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LearningNotebookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LessonStore(context) }
    var lessons by remember { mutableStateOf(store.all()) }
    var stats by remember { mutableStateOf(store.stats()) }
    var pendingDelete by remember { mutableStateOf<LessonStore.Lesson?>(null) }
    val pendingPromotions = remember(lessons) { lessons.filter { !it.promoted && it.hits >= 3 && it.kind != LessonStore.Kind.ERROR } }

    fun refresh() {
        lessons = store.all()
        stats = store.stats()
    }

    SettingsScaffold(title = stringResource(R.string.learning_notebook_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.learning_notebook_summary),
            footer = stringResource(R.string.learning_notebook_footer),
        ) {
            SettingsRow(
                title = stats,
                showChevron = false,
                showDivider = pendingPromotions.isEmpty(),
            )
            pendingPromotions.forEach { lesson ->
                SettingsRow(
                    title = lesson.text,
                    subtitle = stringResource(R.string.learning_promote_hint, lesson.hits),
                    showChevron = false,
                    trailing = {
                        MinisTextButton(onClick = {
                            store.promote(lesson.id)
                            Toast.makeText(
                                context,
                                context.getString(R.string.learning_promoted_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                            refresh()
                        }) {
                            Text(stringResource(R.string.learning_promote_action))
                        }
                    },
                )
            }
        }

        if (lessons.isEmpty()) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.learning_notebook_empty),
                    subtitle = stringResource(R.string.learning_notebook_empty_hint),
                    showDivider = false,
                    showChevron = false,
                )
            }
            return@SettingsScaffold
        }

        for (kind in LessonStore.Kind.values()) {
            val entries = lessons.filter { it.kind == kind }
                .sortedWith(compareByDescending<LessonStore.Lesson> { it.promoted }.thenByDescending { it.updatedAt })
            SettingsSection(
                header = kindTitle(kind) + " · " + entries.size,
            ) {
                if (entries.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.learning_notebook_none_in_journal),
                        showDivider = false,
                        showChevron = false,
                    )
                }
                entries.take(8).forEachIndexed { index, lesson ->
                    SettingsRow(
                        title = lesson.text,
                        subtitle = lessonMeta(lesson),
                        showChevron = false,
                        showDivider = index != entries.take(8).lastIndex,
                        trailing = {
                            IconButton(onClick = { pendingDelete = lesson }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(R.string.learning_forget),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.learning_forget_title)) },
            text = {
                Column {
                    Text(deleting.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.learning_forget_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    store.forget(deleting.id)
                    pendingDelete = null
                    refresh()
                }) {
                    Text(stringResource(R.string.learning_forget_confirm))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun kindTitle(kind: LessonStore.Kind): String = when (kind) {
    LessonStore.Kind.ERROR -> stringResource(R.string.learning_journal_errors)
    LessonStore.Kind.CORRECTION -> stringResource(R.string.learning_journal_corrections)
    LessonStore.Kind.BEST -> stringResource(R.string.learning_journal_best)
    LessonStore.Kind.GAP -> stringResource(R.string.learning_journal_gaps)
}

@Composable
private fun lessonMeta(lesson: LessonStore.Lesson): String {
    val category = when (lesson.category) {
        LessonStore.Category.PREFERENCE -> stringResource(R.string.learning_category_preference)
        LessonStore.Category.WORKFLOW -> stringResource(R.string.learning_category_workflow)
        LessonStore.Category.TECHNICAL -> stringResource(R.string.learning_category_technical)
        LessonStore.Category.IDENTITY -> stringResource(R.string.learning_category_identity)
        LessonStore.Category.PERSONA -> stringResource(R.string.learning_category_persona)
    }
    val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lesson.updatedAt))
    val state = if (lesson.promoted) {
        stringResource(R.string.learning_state_promoted)
    } else {
        stringResource(R.string.learning_state_learning, lesson.hits, lesson.uses)
    }
    return "$category · $state · $date"
}
