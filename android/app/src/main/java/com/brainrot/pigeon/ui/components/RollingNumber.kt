package com.brainrot.pigeon.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.brainrot.pigeon.ui.theme.TextPrimary
import java.text.NumberFormat
import java.util.Locale

private val rollEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/**
 * عداد الإحصائيات: كل رقم له حركة تدحرج رأسية عند تغيّره (فوق عند الزيادة،
 * تحت عند النقصان).
 *
 * البناء بسيط ومقصود: كل خانة `AnimatedContent` بين رقمين، بدون أي ارتفاع
 * ثابت ولا `clipToBounds` ولا `graphicsLayer` يدوي، لذلك الرقم يظهر دائماً
 * داخل إطاره ولا يمكن أن يخرج عن مساحة العرض.
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
    )

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = formatted },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tokens = remember(formatted) { formatted.toList() }

        tokens.forEachIndexed { index, char ->
            key(index, tokens.size) {
                if (char.isDigit()) {
                    AnimatedContent(
                        targetState = char.digitToInt(),
                        transitionSpec = {
                            val up = targetState > initialState
                            val enter = slideInVertically(
                                animationSpec = tween(durationMillis = 560, easing = rollEasing),
                                initialOffsetY = { height -> if (up) height else -height },
                            )
                            val exit = slideOutVertically(
                                animationSpec = tween(durationMillis = 560, easing = rollEasing),
                                targetOffsetY = { height -> if (up) -height else height },
                            )
                            enter togetherWith exit
                        },
                        label = "digit-roll",
                    ) { digit ->
                        Text(
                            text = digit.toString(),
                            style = style,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                } else {
                    Text(
                        text = char.toString(),
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
