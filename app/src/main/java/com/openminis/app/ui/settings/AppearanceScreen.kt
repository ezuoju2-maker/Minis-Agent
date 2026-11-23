package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.data.repository.AppIconRepository
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_THEME_MODE = "theme_mode"
const val KEY_LAUNCH_SESSION = "launch_session"
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"
const val KEY_TOOL_PREVIEW = "tool_preview"
const val KEY_FOLD_TOOLS = "fold_consecutive_tools"
const val FOLD_TOOLS_DEFAULT = true
const val KEY_AUTO_FOCUS_AFTER_REPLY = "chat.autoFocusAfterReply"
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"
const val KEY_AUTO_GROUPING = "autoGroupingEnabled"
const val KEY_FONT_CHAT_INPUT = "font_chat_input"
const val KEY_FONT_MESSAGE = "font_message"
const val KEY_FONT_APP_BASE = "font_app_base"
const val KEY_LANGUAGE = "app_language"

fun returnKeySendsMessage(context: Context): Boolean =
    getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

fun autoGroupingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_GROUPING, true)

fun showChatTitleEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

fun autoExpandThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, true)

private val APP_ICON_TILE_MAX_WIDTH = 192.dp

private val fontScaleLabelRes: List<Int?> = listOf(
    null, R.string.appearance_font_scale_small, R.string.common_default,
    R.string.appearance_font_scale_medium, R.string.appearance_font_scale_large, null,
)
private val fontScaleCodes = listOf("XS", "", "", "", "", "XL")
private val fontScaleValues = listOf(-2, -1, 0, 1, 2, 3)
private val fontScaleMultipliers = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

private data class LanguageOption(val code: String, val flag: String, val label: String)
private val languageOptions = listOf(
    LanguageOption("", "\uD83C\uDF10", ""),
    LanguageOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
    LanguageOption("zh", "\uD83C\uDDE8\uD83C\uDDF3", "简体中文"),
    LanguageOption("zh-Hant", "\uD83C\uDDED\uD83C\uDDF0", "\u7E41\u9AD4\u4E2D\u6587"),
    LanguageOption("ja", "\uD83C\uDDEF\uD83C\uDDF5", "日本語"),
    LanguageOption("ko", "\uD83C\uDDF0\uD83C\uDDF7", "한국어"),
    LanguageOption("fr", "\uD83C\uDDEB\uD83C\uDDF7", "Français"),
    LanguageOption("de", "\uD83C\uDDE9\uD83C\uDDEA", "Deutsch"),
    LanguageOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
    LanguageOption("es", "\uD83C\uDDEA\uD83C\uDDF8", "Espa\u00F1ol"),
    LanguageOption("id", "\uD83C\uDDEE\uD83C\uDDE9", "Bahasa Indonesia"),
    LanguageOption("ms", "\uD83C\uDDF2\uD83C\uDDFE", "Bahasa Melayu"),
    LanguageOption("fil", "\uD83C\uDDF5\uD83C\uDDED", "Filipino"),
    LanguageOption("th", "\uD83C\uDDF9\uD83C\uDDED", "\u0E44\u0E17\u0E22"),
    LanguageOption("tr", "\uD83C\uDDF9\uD83C\uDDF7", "T\u00FCrk\u00E7e"),
    LanguageOption("pl", "\uD83C\uDDF5\uD83C\uDDF1", "Polski"),
    LanguageOption("ro", "\uD83C\uDDF7\uD83C\uDDF4", "Rom\u00E2n\u0103"),
    LanguageOption("pt-BR", "\uD83C\uDDE7\uD83C\uDDF7", "Portugu\u00EAs (Brasil)"),
    LanguageOption("hr", "\uD83C\uDDED\uD83C\uDDF7", "Hrvatski"),
    LanguageOption("it", "\uD83C\uDDEE\uD83C\uDDF9", "Italiano"),
    LanguageOption("nl", "\uD83C\uDDF3\uD83C\uDDF1", "Nederlands"),
    LanguageOption("hu", "\uD83C\uDDED\uD83C\uDDFA", "Magyar"),
    LanguageOption("cs", "\uD83C\uDDE8\uD83C\uDDFF", "\u010Ce\u0161tina"),
    LanguageOption("fa", "\uD83C\uDDEE\uD83C\uDDF7", "\u0641\u0627\u0631\u0633\u06CC"),
)

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

fun fontScaleForLevel(level: Int): Float {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleMultipliers.lastIndex)
    return fontScaleMultipliers[idx]
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onThemeChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var launchSession by remember { mutableIntStateOf(prefs.getInt(KEY_LAUNCH_SESSION, 0)) }
    var returnKeyBehavior by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)) }
    var toolPreview by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_PREVIEW, true)) }
    var foldTools by remember { mutableStateOf(prefs.getBoolean(KEY_FOLD_TOOLS, FOLD_TOOLS_DEFAULT)) }
    var autoFocusAfterReply by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, true)) }
    var confirmBeforeStopping by remember {
        mutableStateOf(prefs.getBoolean(com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY, com.openminis.app.ui.chat.StopConfirmPolicy.DEFAULT_ENABLED))
    }
    var autoExpandThinking by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_EXPAND_THINKING, true)) }
    var showChatTitle by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }
    var autoGrouping by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_GROUPING, true)) }
    var chatInputLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var messageLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var selectedLanguage by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, "") ?: "") }
    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    val tilePurple = Color(0xFF5856D6)
    val tileBlue = Color(0xFF007AFF)
    val tileOrange = Color(0xFFFF9500)
    val tileGreen = Color(0xFF34C759)
    val tileTeal = Color(0xFF5AC8FA)

    SettingsScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {

        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            data class ThemeRow(val label: String, val icon: ImageVector, val tint: Color)
            val themeRows = listOf(
                ThemeRow(stringResource(R.string.appearance_theme_system), Icons.Outlined.BrightnessAuto, tilePurple),
                ThemeRow(stringResource(R.string.appearance_theme_light), Icons.Outlined.LightMode, tileOrange),
                ThemeRow(stringResource(R.string.appearance_theme_dark), Icons.Outlined.DarkMode, tilePurple),
            )
            themeRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = themeMode == idx,
                    onSelect = {
                        themeMode = idx
                        prefs.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < themeRows.size - 1,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_launch),
            footer = stringResource(R.string.appearance_launch_footer),
        ) {
            data class LaunchRow(val label: String, val icon: ImageVector, val tint: Color)
            val launchRows = listOf(
                LaunchRow(stringResource(R.string.appearance_launch_auto), Icons.Outlined.Bolt, tileBlue),
                LaunchRow(stringResource(R.string.appearance_launch_last), Icons.Outlined.History, tileTeal),
                LaunchRow(stringResource(R.string.appearance_launch_new), Icons.Outlined.ChatBubbleOutline, tileGreen),
                LaunchRow(stringResource(R.string.appearance_launch_home), Icons.Outlined.Home, tileBlue),
            )
            launchRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = launchSession == idx,
                    onSelect = {
                        launchSession = idx
                        prefs.edit().putInt(KEY_LAUNCH_SESSION, idx).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < launchRows.size - 1,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
            footer = stringResource(R.string.appearance_return_key_footer),
        ) {
            data class ReturnRow(val label: String, val value: Int)
            val returnRows = listOf(
                ReturnRow(stringResource(R.string.appearance_return_key_newline), 0),
                ReturnRow(stringResource(R.string.appearance_return_key_send), 1),
            )
            returnRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = returnKeyBehavior == row.value,
                    onSelect = {
                        returnKeyBehavior = row.value
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, row.value).apply()
                    },
                    leading = {
                        if (idx == 0) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                tint = tilePurple,
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                tint = tileGreen,
                            )
                        }
                    },
                    showDivider = idx < returnRows.size - 1,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_keep_awake),
            footer = stringResource(R.string.appearance_keep_awake_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ScreenLockPortrait,
                iconColor = tileGreen,
                title = stringResource(R.string.appearance_keep_awake_title),
                checked = keepScreenAwake,
                onCheckedChange = {
                    keepScreenAwake = it
                    prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_tool_preview),
            footer = stringResource(R.string.appearance_tool_preview_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_preview_title),
                checked = toolPreview,
                onCheckedChange = {
                    toolPreview = it
                    prefs.edit().putBoolean(KEY_TOOL_PREVIEW, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.tool_fold_section),
            footer = stringResource(R.string.tool_fold_setting_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.UnfoldLess,
                iconColor = tileTeal,
                title = stringResource(R.string.tool_fold_setting_title),
                checked = foldTools,
                onCheckedChange = {
                    foldTools = it
                    prefs.edit().putBoolean(KEY_FOLD_TOOLS, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_deep_thinking),
            footer = stringResource(R.string.appearance_auto_expand_thinking_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_auto_focus_after_reply),
            footer = stringResource(R.string.appearance_auto_focus_after_reply_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Keyboard,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_focus_after_reply_title),
                checked = autoFocusAfterReply,
                onCheckedChange = {
                    autoFocusAfterReply = it
                    prefs.edit().putBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            footer = stringResource(R.string.appearance_confirm_before_stopping_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.StopCircle,
                iconColor = Color(0xFFFF3B30),
                title = stringResource(R.string.appearance_confirm_before_stopping_title),
                checked = confirmBeforeStopping,
                onCheckedChange = {
                    confirmBeforeStopping = it
                    prefs.edit().putBoolean(com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_title),
            footer = stringResource(R.string.appearance_show_chat_title_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_show_chat_title),
                subtitle = stringResource(R.string.appearance_show_chat_title_subtitle),
                checked = showChatTitle,
                onCheckedChange = {
                    showChatTitle = it
                    prefs.edit().putBoolean(KEY_SHOW_CHAT_TITLE, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_grouping),
            footer = stringResource(R.string.appearance_auto_grouping_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Folder,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_grouping),
                subtitle = stringResource(R.string.appearance_auto_grouping_subtitle),
                checked = autoGrouping,
                onCheckedChange = {
                    autoGrouping = it
                    prefs.edit().putBoolean(KEY_AUTO_GROUPING, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
            footer = stringResource(R.string.appearance_font_size_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.FormatSize,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_app_icon),
            footer = stringResource(R.string.appearance_app_icon_footer),
        ) {
            data class IconOption(
                val variant: AppIconRepository.Variant,
                val titleRes: Int,
                val mipmapRes: Int,
            )
            val iconOptions = listOf(
                IconOption(
                    AppIconRepository.Variant.Auto,
                    R.string.appearance_app_icon_auto,
                    R.mipmap.ic_launcher,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicLight,
                    R.string.appearance_app_icon_light,
                    R.mipmap.ic_launcher_classic_light,
                ),
                IconOption(
                    AppIconRepository.Variant.ClassicDark,
                    R.string.appearance_app_icon_dark,
                    R.mipmap.ic_launcher_classic_dark,
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp,
                    Alignment.CenterHorizontally,
                ),
            ) {
                for (option in iconOptions) {
                    val isSelected = selectedAppIcon == option.variant
                    val iconPainter: Painter = remember(option.mipmapRes) {
                        val drawable = ResourcesCompat.getDrawable(
                            context.resources,
                            option.mipmapRes,
                            context.theme,
                        )
                        if (drawable != null) {
                            BitmapPainter(
                                drawable.toBitmap(width = 192, height = 192).asImageBitmap()
                            )
                        } else {
                            BitmapPainter(
                                android.graphics.Bitmap
                                    .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                                    .asImageBitmap()
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .widthIn(max = APP_ICON_TILE_MAX_WIDTH)
                            .clickable {
                                if (selectedAppIcon != option.variant) {
                                    selectedAppIcon = option.variant
                                    AppIconRepository.apply(context, option.variant)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                ),
                        ) {
                            Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .background(
                                            color = Color.White,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(option.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
            footer = stringResource(R.string.appearance_language_footer),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.label,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val lm = context.getSystemService(LocaleManager::class.java)
                            lm?.applicationLocales = if (lang.code.isEmpty()) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(lang.code)
                            }
                        } else {
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    leading = {
                        Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    },
                    showDivider = idx < languageOptions.size - 1,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val labelIdx = sliderPos.roundToInt().coerceIn(0, fontScaleCodes.lastIndex)
    val currentLabel = fontScaleLabelRes[labelIdx]?.let { stringResource(it) } ?: fontScaleCodes[labelIdx]

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
