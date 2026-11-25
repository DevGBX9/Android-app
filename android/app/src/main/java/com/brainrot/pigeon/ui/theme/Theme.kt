package com.brainrot.pigeon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Background = Color(0xFF0C1015)
val BarSurface = Color(0xFF11161D)
val CardSurface = Color(0xFF151A21)
val Bubble = Color(0xFF2B3136)
val BubbleText = Color(0xFFC9CED3)
val TextPrimary = Color(0xFFF5F5F5)
val TextMuted = Color(0xFF8C9298)
val Outline = Color(0xFF2E353C)
// لون إنستغرام المستخدم في شارة التسجيل وأيقونة نافذة المشاركة.
val Accent = Color(0xFFE1306C)

// حاويات Material 3: درجات أسطح متتابعة تستخدمها مكوّنات M3 (TimePicker،
// DatePicker، البطاقات) بدل الألوان الافتراضية البنفسجية.
private val SurfaceContainerLowest = Color(0xFF080B0F)
private val SurfaceContainerLow = Color(0xFF10151B)
private val SurfaceContainer = Color(0xFF141A21)
private val SurfaceContainerHigh = Color(0xFF191F27)
private val SurfaceContainerHighest = Color(0xFF1F262F)

/**
 * سمة التطبيق الداكنة بستايل Material You: اللون الأساسي هو لون إنستغرام
 * (Accent) حتى تظهر مؤشرات الاختيار في TimePicker/DatePicker بنفس هوية
 * التطبيق بدل الأبيض.
 */
@Composable
fun PigeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF4A1226),
            onPrimaryContainer = Color(0xFFFFD9E3),
            secondary = BubbleText,
            onSecondary = Background,
            secondaryContainer = Color(0xFF262E37),
            onSecondaryContainer = TextPrimary,
            tertiary = Color(0xFF8A6CFF),
            onTertiary = Color.White,
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
            outlineVariant = Color(0xFF232A31),
            inverseSurface = TextPrimary,
            inverseOnSurface = Background,
            inversePrimary = Accent,
            error = Color(0xFFFF7A7A),
            onError = Color(0xFF3B0A0A),
            errorContainer = Color(0xFF5A1414),
            onErrorContainer = Color(0xFFFFD9D9),
        ),
        typography = Typography(
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            ),
        ),
        content = content,
    )
}
