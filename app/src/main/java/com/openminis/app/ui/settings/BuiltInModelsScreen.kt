package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType

data class BuiltInPlatform(
    val id: String,
    val name: String,
    val subtitle: String,
    val logoRes: Int,
    val accent: androidx.compose.ui.graphics.Color,
    val platformId: String? = null,
    val providerType: ProviderType? = null,
    val regions: List<String> = emptyList(),
)

val builtInPlatforms: List<BuiltInPlatform> = listOf(
    BuiltInPlatform(
        id = "workbuddy",
        name = "WorkBuddy",
        subtitle = "账号登录取额度 · 国内 / 国际双站点",
        logoRes = R.drawable.ic_workbuddy,
        accent = androidx.compose.ui.graphics.Color(0xFF0EC8A9),
        platformId = "workbuddy",
        regions = listOf("国内版", "国际版"),
    ),
    BuiltInPlatform(
        id = "qoder",
        name = "Qoder",
        subtitle = "阿里 Qoder（qoder.cn / qoder.com）",
        logoRes = R.drawable.ic_qoder,
        accent = androidx.compose.ui.graphics.Color(0xFF111111),
        platformId = "qoder",
        regions = listOf("国内版", "国际版"),
    ),
    BuiltInPlatform(
        id = "minimax",
        name = "MiniMax Code",
        subtitle = "MiniMax M 系列（minimax.cn / minimax.io）",
        logoRes = R.drawable.ic_minimax,
        accent = androidx.compose.ui.graphics.Color(0xFFE73562),
        platformId = "minimax",
        regions = listOf("国内版", "国际版"),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInModelsScreen(
    onBack: () -> Unit,
    onOpenPlatform: (String) -> Unit,
    onOpenProviderType: (ProviderType) -> Unit,
) {
    SettingsScaffold(title = "内置模型", onBack = onBack) {
        Text(
            text = "选择一个平台，登录账号即可直接使用其内置模型额度，无需填写 API Key 或接口地址。" +
                "分国内外两站的平台可在详情页里切换，两个站点的账号互不通用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            builtInPlatforms.forEach { platform ->
                PlatformCard(
                    platform = platform,
                    onClick = {
                        val pid = platform.platformId
                        val type = platform.providerType
                        when {
                            pid != null -> onOpenPlatform(pid)
                            type != null -> onOpenProviderType(type)
                        }
                    },
                )
            }
        }
        Text(
            text = "以上平台都由本应用直接对接其官方登录协议，用你的账号登录即可使用其内置额度，不需要手动配置接口地址或模型列表。" +
                "这些均为第三方非官方对接，接口可能随官方客户端更新而变化。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PlatformCard(platform: BuiltInPlatform, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(platform.logoRes),
                contentDescription = platform.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = platform.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (platform.regions.size > 1) {
                    platform.regions.forEach { label ->
                        Text(
                            text = label,
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = platform.accent,
                            modifier = Modifier
                                .padding(start = 5.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(platform.accent.copy(alpha = 0.10f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = platform.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
