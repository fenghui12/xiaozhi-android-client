package me.xiaozhi.androidclient.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 配色：2026-11 前端改版定为「奶油杏底 + 珊瑚橙」的暖调陪伴风（效果图见
// 项目情况/前端改版效果图_2026-11-02/），替换原来偏工具感的冷调品牌蓝。
private val Brand = Color(0xFFFF7F50)
private val BrandSoft = Color(0xFFFFE6D9)
private val BrandDeep = Color(0xFFB4471F)
private val Mist = Color(0xFF6F98C4)
private val Mint = Color(0xFF4FB38A)
private val Paper = Color(0xFFFFF6EE)
private val Sand = Color(0xFFF6E9DE)
private val Ink = Color(0xFF4A3426)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDeep,
    secondary = Mist,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDF8),
    onSecondaryContainer = Color(0xFF233A55),
    tertiary = Mint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF3E8),
    onTertiaryContainer = Color(0xFF14402F),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Color(0xFF8C7466),
    outline = Color(0xFFE2CFC1),
    outlineVariant = Color(0xFFF1E2D6),
    error = Color(0xFFD9442B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE1D9),
    onErrorContainer = Color(0xFF5A1406),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun XiaozhiClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
