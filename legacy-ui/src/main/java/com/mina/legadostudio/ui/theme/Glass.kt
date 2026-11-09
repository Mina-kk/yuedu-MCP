package com.mina.legadostudio.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

val LocalStudioHaze = compositionLocalOf<HazeState?> { null }

/** 全屏覆盖层（如 HTTP 详情）打开时置 true，StudioApp 据此隐藏底部胶囊 */
val LocalStudioFullscreen = compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }


/** 全局唯一的 HazeState：列表做 hazeSource，悬浮条做 hazeEffect */
@Composable
fun ProvideStudioHaze(content: @Composable () -> Unit) {
    val state = dev.chrisbanes.haze.rememberHazeState()
    androidx.compose.runtime.CompositionLocalProvider(LocalStudioHaze provides state, content = content)
}

/** 底部 Tab 定义 */
data class StudioTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * 顶栏/底栏避让用户可交互的系统栏区域（手势条/三键/状态栏）。
 * 注意：顶栏本身采用液态玻璃背景，内容区域只额外加 navigationBars 避让，
 * 避免与半透明底栏叠加出双倍的底部留白。
 */
@Composable
fun studioTopInset(): Dp = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }

@Composable
fun studioBottomInset(): Dp = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

/**
 * 液态玻璃质感（高斯模糊）：
 * - 白色玻璃：半透明白底 + 柔和白 tint + 24dp 模糊，noise 关掉（噪点会显脏显灰）。
 * - 深色玻璃：半透明深底 + 黑色 tint，保证夜间文字可读。
 * 只用于顶栏/底部 Tab 等悬浮条，正文卡片保持纯白以保证阅读清晰。
 */
@Composable
fun liquidGlassStyle(darkTheme: Boolean = LocalStudioDark.current): HazeStyle {
    return if (darkTheme) {
        HazeStyle(
            backgroundColor = Color(0xFF000000),
            tints = listOf(HazeTint(Color(0xFF000000).copy(alpha = 0.55f))),
            blurRadius = 24.dp,
            noiseFactor = 0f,
        )
    } else {
        HazeStyle(
            backgroundColor = Color.White,
            tints = listOf(HazeTint(Color.White.copy(alpha = 0.6f))),
            blurRadius = 24.dp,
            noiseFactor = 0f,
        )
    }
}

/** 毛玻璃悬浮条背后衬一层极淡的实色，保证列表滚动经过时文字不糊成一片 */
private fun glassFallbackColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xD9000000) else Color(0xD9FFFFFF)

@Composable
fun studioFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
fun studioChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = Color.Transparent,
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun studioChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outline,
    selectedBorderColor = Color.Transparent,
)

@Composable
fun glassFill(): Color = if (LocalStudioDark.current) Color(0xFF1C1C1E) else Color.White

/** iOS 风纯白卡片：细圆角 + 柔和投影，无描边、无玻璃噪声。内容自带内边距 */
@Composable
fun GlassCard(
    fill: Color = glassFill(),
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .shadow(6.dp, shape, spotColor = Color(0x14000000), ambientColor = Color(0x0A000000))
            .clip(shape)
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) { content() }
}

/** 顶部标题栏：液态玻璃（高斯模糊）+ 极细分隔线，并避让状态栏 */
@Composable
fun GlassTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haze = LocalStudioHaze.current
    val hairline = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    Column(
        modifier
            .fillMaxWidth()
            .background(glassFallbackColor(LocalStudioDark.current))
            .then(if (haze != null) Modifier.hazeEffect(state = haze, style = liquidGlassStyle()) else Modifier)
            .statusBarsPadding()
            .drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, size.height - 0.5.dp.toPx()),
                    end = Offset(size.width, size.height - 0.5.dp.toPx()),
                    strokeWidth = 0.5.dp.toPx(),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                    )
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (actions != null) actions()
        }
    }
}

/** 底部 Tab：iOS 风液态玻璃胶囊（高斯模糊 + 白色高光描边），避让手势条 */
@Composable
fun GlassTabBar(
    tabs: List<StudioTab>,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haze = LocalStudioHaze.current
    val dark = LocalStudioDark.current
    val pillShape = RoundedCornerShape(30.dp)
    val rim = if (dark) Color(0x33FFFFFF) else Color(0x99FFFFFF)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(12.dp, pillShape, spotColor = Color(0x1F000000), ambientColor = Color(0x0F000000))
            .clip(pillShape)
            .background(glassFallbackColor(dark))
            .then(if (haze != null) Modifier.hazeEffect(state = haze, style = liquidGlassStyle(dark)) else Modifier)
            .drawBehind {
                // 顶部一道玻璃高光，模拟液态玻璃的折射边
                drawLine(
                    color = rim,
                    start = Offset(18.dp.toPx(), 0.75.dp.toPx()),
                    end = Offset(size.width - 18.dp.toPx(), 0.75.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == current
            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.Icon(tab.icon, contentDescription = tab.label, tint = tint)
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

/** 方形柔和底色的图标容器（iOS 设置页风格） */
@Composable
fun TonalIconBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    size: Int = 44,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = contentColor)
    }
}

/** iOS 风分段选择器：圆角滑块 + 等宽分段，用于书源类型等单选开关 */
@Composable
fun StudioSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(12.dp)
    val thumbShape = RoundedCornerShape(9.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(trackShape)
            .background(if (LocalStudioDark.current) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .then(
                        if (selected) {
                            Modifier
                                .shadow(2.dp, thumbShape, spotColor = Color(0x1A000000), ambientColor = Color(0x0D000000))
                                .clip(thumbShape)
                                .background(if (LocalStudioDark.current) MaterialTheme.colorScheme.surfaceContainerHighest else glassFill())
                        } else {
                            Modifier.clip(thumbShape)
                        },
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
