package com.brainrot.pigeon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.brainrot.pigeon.ui.theme.TextPrimary
import java.text.NumberFormat
import java.util.Locale

private val rollEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
private const val DIGIT_HEIGHT_EM = 1.15f

/**
 * نفس عداد الموقع: كل خانة رقم تتدحرج للرقم الجديد (فوق إذا زاد، تحت إذا نقص)،
 * وعند تغيّر عدد الخانات (999 ← 1,000) يعاد البناء مع أنيميشن pop خفيف.
 */
@Composable
fun RollingNumber(
    value: Int?,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    val formatted = remember(value) {
        value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it.toLong()) } ?: "—"
    }

    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = TextPrimary,
        fontFeatureSettings = "tnum",
    )

    val digitHeight = with(LocalDensity.current) { (fontSize.toPx() * DIGIT_HEIGHT_EM).toDp() }

    val pop = remember { Animatable(1f) }
    var lastLength by remember { mutableIntStateOf(formatted.length) }

    LaunchedEffect(formatted.length) {
        if (formatted.length != lastLength) {
            lastLength = formatted.length
            pop.snapTo(0f)
            pop.animateTo(1f, tween(durationMillis = 340))
        }
    }

    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = formatted }
            .graphicsLayer {
                alpha = 0.35f + 0.65f * pop.value
                translationY = (1f - pop.value) * 5.dp.toPx()
            },
        verticalAlignment = Alignment.Top,
    ) {
        val tokens = remember(formatted) { formatted.toList() }

        tokens.forEachIndexed { index, char ->
            key(index, tokens.size) {
                if (char.isDigit()) {
                    RollingDigit(
                        digit = char.digitToInt(),
                        digitHeight = digitHeight,
                        style = style,
                    )
                } else {
                    Box(
                        modifier = Modifier.height(digitHeight).clipToBounds(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = char.toString(), style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun RollingDigit(digit: Int, digitHeight: Dp, style: TextStyle) {
    var current by remember { mutableIntStateOf(digit) }
    var upward by remember { mutableStateOf(true) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(digit) {
        if (digit == current) return@LaunchedEffect
        upward = digit > current
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 720, easing = rollEasing))
        // الترتيب مهم: نثبّت الرقم الجديد قبل ما نصفّر الحركة حتى ما تلمع خانة قديمة
        current = digit
        progress.snapTo(0f)
    }

    val top = if (upward) current else digit
    val bottom = if (upward) digit else current
    val heightPx = with(LocalDensity.current) { digitHeight.toPx() }

    Box(
        modifier = Modifier.height(digitHeight).clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationY = if (upward) {
                    -progress.value * heightPx
                } else {
                    -(1f - progress.value) * heightPx
                }
            },
        ) {
            DigitCell(value = top, height = digitHeight, style = style)
            DigitCell(value = bottom, height = digitHeight, style = style)
        }
    }
}

@Composable
private fun DigitCell(value: Int, height: Dp, style: TextStyle) {
    Box(Modifier.height(height), contentAlignment = Alignment.Center) {
        Text(text = value.toString(), style = style, maxLines = 1, softWrap = false)
    }
}
