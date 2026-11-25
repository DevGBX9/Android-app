package com.brainrot.pigeon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brainrot.pigeon.R

// لوحة واحدة بلا أي لون: أبيض وأسود وتدرّجات رمادية محايدة بينهما فقط.
val Background = Color(0xFF0B0B0B)
val BarSurface = Color(0xFF121212)
val CardSurface = Color(0xFF161616)
val Bubble = Color(0xFF282828)
val BubbleText = Color(0xFFD2D2D2)
val TextPrimary = Color(0xFFF7F7F7)
val TextMuted = Color(0xFF9C9C9C)
val Outline = Color(0xFF2E2E2E)
// الإبراز أبيض: إطار الريل المفتوح، وعلامة النشر، والعدّاد.
val Accent = Color(0xFFF2F2F2)

// حاويات Material 3: درجات أسطح متتابعة تستخدمها مكوّنات M3 (TimePicker،
// DatePicker، البطاقات) بدل الألوان الافتراضية البنفسجية.
private val SurfaceContainerLowest = Color(0xFF070707)
private val SurfaceContainerLow = Color(0xFF101010)
private val SurfaceContainer = Color(0xFF151515)
private val SurfaceContainerHigh = Color(0xFF1B1B1B)
private val SurfaceContainerHighest = Color(0xFF232323)

/**
 * خط Inter: حواف أوسع وارتفاع حروف أوضح من الخط الافتراضي، فيبقى النص
 * الصغير (تاريخ الريل والعدّاد) مقروءاً بلا إجهاد.
 */
val PigeonFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun pigeonStyle(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = PigeonFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private val PigeonTypography = Typography(
    displayLarge = pigeonStyle(56, 64, FontWeight.Normal),
    displayMedium = pigeonStyle(44, 52, FontWeight.Normal),
    displaySmall = pigeonStyle(36, 44, FontWeight.SemiBold),
    headlineLarge = pigeonStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = pigeonStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = pigeonStyle(24, 32, FontWeight.SemiBold),
    titleLarge = pigeonStyle(20, 28, FontWeight.SemiBold),
    titleMedium = pigeonStyle(17, 24, FontWeight.SemiBold),
    titleSmall = pigeonStyle(15, 21, FontWeight.Medium),
    bodyLarge = pigeonStyle(15, 22, FontWeight.Normal),
    bodyMedium = pigeonStyle(14, 20, FontWeight.Normal),
    bodySmall = pigeonStyle(12, 18, FontWeight.Normal),
    labelLarge = pigeonStyle(14, 19, FontWeight.SemiBold),
    labelMedium = pigeonStyle(12, 17, FontWeight.Medium),
    labelSmall = pigeonStyle(11, 15, FontWeight.Medium),
)

/** سمة التطبيق الداكنة: أسطح رمادية ونصوص بيضاء، بلا أي لون ثالث. */
@Composable
fun PigeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Background,
            primaryContainer = Color(0xFF2C2C2C),
            onPrimaryContainer = TextPrimary,
            secondary = BubbleText,
            onSecondary = Background,
            secondaryContainer = Color(0xFF242424),
            onSecondaryContainer = TextPrimary,
            tertiary = TextPrimary,
            onTertiary = Background,
            background = Background,
            onBackground = TextPrimary,
            surface = CardSurface,
            onSurface = TextPrimary,
            surfaceVariant = Bubble,
            onSurfaceVariant = BubbleText,
            surfaceTint = Accent,
            surfaceBright = CardSurface,
            surfaceDim = Background,
            surfaceContainerLowest = SurfaceContainerLowest,
            surfaceContainerLow = SurfaceContainerLow,
            surfaceContainer = SurfaceContainer,
            surfaceContainerHigh = SurfaceContainerHigh,
            surfaceContainerHighest = SurfaceContainerHighest,
            outline = Outline,
            outlineVariant = Color(0xFF232323),
            inverseSurface = TextPrimary,
            inverseOnSurface = Background,
            inversePrimary = Background,
            error = Color(0xFFE6E6E6),
            onError = Color(0xFF101010),
            errorContainer = Color(0xFF2A2A2A),
            onErrorContainer = Color(0xFFF5F5F5),
        ),
        typography = PigeonTypography,
        content = content,
    )
}
