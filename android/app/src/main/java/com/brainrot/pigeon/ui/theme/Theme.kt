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

@Composable
fun PigeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TextPrimary,
            onPrimary = Background,
            secondary = BubbleText,
            onSecondary = Background,
            background = Background,
            onBackground = TextPrimary,
            surface = CardSurface,
            onSurface = TextPrimary,
            surfaceVariant = Bubble,
            onSurfaceVariant = BubbleText,
            outline = Outline,
            error = Color(0xFFFF7A7A),
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
