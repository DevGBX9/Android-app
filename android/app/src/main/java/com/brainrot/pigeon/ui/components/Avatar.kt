package com.brainrot.pigeon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.AvatarUiState
import com.brainrot.pigeon.ui.theme.Background
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * صورة البروفايل: الصورة القديمة تتحول رمادي/أغمق ("جاري التحميل") بينما
 * الجديدة تطفو فوقها وتكبر من 1.06 إلى 1، تماماً مثل الموقع.
 */
@Composable
fun Avatar(
    state: AvatarUiState,
    size: Dp,
    onLanded: () -> Unit,
    onClick: () -> Unit,
) {
    val incoming = state.incoming
    val fallback = painterResource(R.drawable.avatar_fallback)

    val grayscale = animateFloatAsState(
        targetValue = if (incoming != null) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "avatar-grayscale",
    )

    val fade = remember { Animatable(0f) }
    val zoom = remember { Animatable(1.06f) }

    LaunchedEffect(incoming) {
        if (incoming == null) {
            fade.snapTo(0f)
            zoom.snapTo(1.06f)
            return@LaunchedEffect
        }

        fade.snapTo(0f)
        zoom.snapTo(1.06f)
        coroutineScope {
            launch { fade.animateTo(1f, tween(durationMillis = 520)) }
            launch {
                zoom.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 720,
                        easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f),
                    ),
                )
            }
        }
        delay(60)
        onLanded()
    }

    Box(Modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            Image(
                painter = fallback,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )

            state.base?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(grayscaleMatrix(grayscale.value)),
                    modifier = Modifier.matchParentSize(),
                )
            }

            if (incoming != null) {
                Image(
                    bitmap = incoming,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = fade.value
                            scaleX = zoom.value
                            scaleY = zoom.value
                        },
                )
            }
        }

        // شارة + أسفل الصورة، مثل الموقع
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(21.dp)
                .background(Color(0xFFF5F5F5), CircleShape)
                .border(2.dp, Background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(9.dp)) {
                val stroke = 1.8.dp.toPx()
                drawLine(
                    color = Background,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Background,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** دمج التشبع (grayscale) مع تخفيت بسيط، مطابق لـ grayscale(1) brightness(.72). */
private fun grayscaleMatrix(amount: Float): ColorMatrix {
    val a = amount.coerceIn(0f, 1f)
    val s = 1f - a
    val brightness = 1f - 0.28f * a

    val lr = 0.2126f
    val lg = 0.7152f
    val lb = 0.0722f

    return ColorMatrix(
        floatArrayOf(
            (lr + s * (1f - lr)) * brightness,
            (lg - lg * s) * brightness,
            (lb - lb * s) * brightness,
            0f,
            0f,
            (lr - lr * s) * brightness,
            (lg + s * (1f - lg)) * brightness,
            (lb - lb * s) * brightness,
            0f,
            0f,
            (lr - lr * s) * brightness,
            (lg - lg * s) * brightness,
            (lb + s * (1f - lb)) * brightness,
            0f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    )
}
