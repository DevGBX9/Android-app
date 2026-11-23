package com.brainrot.pigeon.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainrot.pigeon.data.NOTE_TEXT
import com.brainrot.pigeon.data.Profile
import com.brainrot.pigeon.ui.AvatarUiState
import com.brainrot.pigeon.ui.theme.Bubble
import com.brainrot.pigeon.ui.theme.BubbleText
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary

private val ShimmerBase = Color(0xFF232A31)
private val ShimmerHighlight = Color(0xFF3B454F)

/**
 * كرت الحساب: الببل + الصورة على اليسار، والاسم + البايو على اليمين، ثم صف
 * الإحصائيات بعرض الكرت كامل حتى تأخذ كل خانة مساحتها ولا ينقصّ أي رقم.
 * أثناء أول جلب تظهر أشرطة Skeleton متحركة بدل الأرقام.
 */
@Composable
fun ProfileCard(
    profile: Profile,
    avatar: AvatarUiState,
    loading: Boolean,
    onAvatarLanded: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp

        val avatarSize = if (compact) 76.dp else 88.dp
        val nameSize = if (compact) 15.sp else 17.sp
        val bioSize = if (compact) 13.sp else 14.sp
        val statValueSize = if (compact) 15.sp else 17.sp
        val statLabelSize = if (compact) 11.sp else 12.sp
        val statGap = if (compact) 8.dp else 14.dp

        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.width(avatarSize),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NoteBubble(
                        width = avatarSize,
                        minHeight = avatarSize * 0.57f,
                    )
                    Spacer(Modifier.height(6.dp))
                    Avatar(
                        state = avatar,
                        size = avatarSize,
                        onLanded = onAvatarLanded,
                        onClick = onAvatarClick,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = profile.username,
                            color = TextPrimary,
                            fontSize = nameSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        VerifiedBadge(Modifier.size(17.dp))
                    }

                    Text(
                        text = profile.bio.ifBlank { " " },
                        color = TextPrimary,
                        fontSize = bioSize,
                        lineHeight = (bioSize.value + 5).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(statGap),
            ) {
                StatColumn(
                    modifier = Modifier.weight(1f),
                    value = profile.posts,
                    label = "posts",
                    loading = loading,
                    valueSize = statValueSize,
                    labelSize = statLabelSize,
                )
                StatColumn(
                    modifier = Modifier.weight(1f),
                    value = profile.followers,
                    label = "followers",
                    loading = loading,
                    valueSize = statValueSize,
                    labelSize = statLabelSize,
                )
                StatColumn(
                    modifier = Modifier.weight(1f),
                    value = profile.following,
                    label = "following",
                    loading = loading,
                    valueSize = statValueSize,
                    labelSize = statLabelSize,
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    modifier: Modifier,
    value: Int?,
    label: String,
    loading: Boolean,
    valueSize: TextUnit,
    labelSize: TextUnit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier.height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                ShimmerBar(width = 40.dp, height = 14.dp)
            } else {
                RollingNumber(value = value, fontSize = valueSize)
            }
        }
        Text(
            text = label,
            color = TextMuted,
            fontSize = labelSize,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** شريط تحميل لامع يستخدم أثناء أول جلب للبيانات بدل الأرقام. */
@Composable
private fun ShimmerBar(width: Dp, height: Dp) {
    val transition = rememberInfiniteTransition(label = "stat-skeleton")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stat-skeleton-shift",
    )

    val widthPx = with(LocalDensity.current) { width.toPx() }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    colors = listOf(ShimmerBase, ShimmerHighlight, ShimmerBase),
                    start = Offset(shift * widthPx * 2f, 0f),
                    end = Offset(shift * widthPx * 2f + widthPx, 0f),
                ),
            ),
    )
}

@Composable
private fun NoteBubble(width: Dp, minHeight: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(Bubble)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = NOTE_TEXT,
            color = BubbleText,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
fun VerifiedBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = size.minDimension / 24f

        drawCircle(
            color = TextPrimary,
            radius = 10f * scale,
            style = Stroke(
                width = 2f * scale,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(2.4f * scale, 2.6f * scale),
                ),
            ),
        )

        val check = Path().apply {
            moveTo(7.6f * scale, 12.3f * scale)
            lineTo(10.5f * scale, 15.2f * scale)
            lineTo(16.4f * scale, 9.1f * scale)
        }

        drawPath(
            path = check,
            color = TextPrimary,
            style = Stroke(
                width = 2.2f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
