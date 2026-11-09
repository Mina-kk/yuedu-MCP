package com.mina.legadostudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读书源MCP视觉主题：iOS 简约白。
 * 页面底、卡片、顶栏、底栏、芯片全白；主色 iOS 蓝 #007AFF；文字用 iOS 灰阶。
 * 玻璃层次感依靠 0.5dp 发丝描边与柔和投影，不使用色块。
 */
private val Light = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = Color(0xFF003A75),
    secondary = Color(0xFF8A8A8E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2F2F7),
    onSecondaryContainer = Color(0xFF3A3A3C),
    tertiary = Color(0xFF5856D6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBEBFF),
    onTertiaryContainer = Color(0xFF1C1B4D),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF5F0A05),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF9F9FB),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF2F2F7),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0B3B66),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = Color(0xFF98989F),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFF7D7AFF),
    onTertiary = Color(0xFF12123B),
    tertiaryContainer = Color(0xFF2C2B66),
    onTertiaryContainer = Color(0xFFE4E3FF),
    error = Color(0xFFFF453A),
    onError = Color(0xFF3B0805),
    errorContainer = Color(0xFF5F100B),
    onErrorContainer = Color(0xFFFFD6D2),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF98989F),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF2C2C2E),
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val StudioTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
)

val LocalStudioDark = androidx.compose.runtime.compositionLocalOf { false }
@Composable
fun StudioTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = ThemeMode.resolveDark(mode, isSystemInDarkTheme())
    androidx.compose.runtime.CompositionLocalProvider(LocalStudioDark provides dark) {
    val scheme = if (dark) Dark else Light
    MaterialTheme(
        colorScheme = scheme,
        shapes = StudioShapes,
        typography = StudioTypography,
    ) {
        // 玻璃系组件均为自绘容器，必须显式下发内容色，否则深色下默认黑字不可见
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
    }
}
