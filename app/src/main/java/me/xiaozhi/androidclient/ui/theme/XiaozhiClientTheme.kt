package me.xiaozhi.androidclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 配色从原来的「鸢尾紫 + 兰花紫」换成一套冷静的品牌蓝。
// 原方案在真机上偏灰紫、控件和背景互相抢，看着像未完成的默认主题。
private val Brand = Color(0xFF3E7BFA)
private val BrandSoft = Color(0xFFDCE8FF)
private val BrandDeep = Color(0xFF1E4FB8)
private val Teal = Color(0xFF3FA2B8)
private val Mint = Color(0xFF3F8F7A)
private val Paper = Color(0xFFF1F5FA)
private val Stone = Color(0xFFE3EAF3)
private val Ink = Color(0xFF16202E)
private val Night = Color(0xFF10161F)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDeep,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EEF4),
    onSecondaryContainer = Color(0xFF10404C),
    tertiary = Mint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F1EA),
    onTertiaryContainer = Color(0xFF123B34),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Stone,
    onSurfaceVariant = Color(0xFF566173),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFE1BEE7),
    onSecondary = Color(0xFF43224C),
    secondaryContainer = Color(0xFF5B3865),
    onSecondaryContainer = Color(0xFFF8D8FF),
    tertiary = Color(0xFFA9D5CB),
    onTertiary = Color(0xFF123B34),
    tertiaryContainer = Color(0xFF2B524A),
    onTertiaryContainer = Color(0xFFD9F1EA),
    background = Night,
    onBackground = Color(0xFFEAE0F2),
    surface = Color(0xFF201A29),
    onSurface = Color(0xFFEAE0F2),
    surfaceVariant = Color(0xFF4A4458),
    onSurfaceVariant = Color(0xFFCBC3D8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
