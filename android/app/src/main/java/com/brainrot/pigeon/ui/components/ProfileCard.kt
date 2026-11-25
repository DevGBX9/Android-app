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
import com.brainrot.pigeon.ui.theme.TextPrimary

private val ShimmerBase = Color(0xFF1E1E1E)
private val ShimmerHighlight = Color(0xFF3A3A3A)

// نفس نسب الصورة المرجعية: الأفاتار 76dp، فقاعة أعرض قليلاً من الأفاتار
// ومُلتصقة فيه، البايو تحت العمود الأيسر، والاسم والإحصائيات على اليمين بمحاذاة
// يسار واحدة.
private val AVATAR_SIZE = 76.dp
private val BUBBLE_WIDTH = 74.dp
private val LEFT_INSET = 7.dp
private val COLUMN_GAP = 24.dp

/**
 * كرت الحساب: ببل + أفاتار + بايو على اليسار، والاسم مع الإحصائيات على اليمين.
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
        val compact = maxWidth < 320.dp

        val avatarSize = if (compact) 66.dp else AVATAR_SIZE
        val bubbleWidth = if (compact) 64.dp else BUBBLE_WIDTH
        val nameSize = if (compact) 16.sp else 18.sp
        val bioSize = if (compact) 14.sp else 15.sp
        val statValueSize = if (compact) 16.sp else 18.sp
        val statLabelSize = if (compact) 12.sp else 15.sp
        val statGap = if (compact) 8.dp else 12.dp

        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.padding(start = LEFT_INSET),
                    horizontalAlignment = Alignment.Start,
                ) {
                    NoteBubble(width = bubbleWidth, minHeight = if (compact) 34.dp else 38.dp)
                    Spacer(Modifier.height(3.dp))
                    Avatar(
                        state = avatar,
                        size = avatarSize,
                        onLanded = onAvatarLanded,
                        onClick = onAvatarClick,
                    )
                }

                Spacer(Modifier.width(COLUMN_GAP))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = if (compact) 18.dp else 22.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                        VerifiedBadge(Modifier.size(if (compact) 14.dp else 15.dp))
                    }

                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                    // وزن متساوٍ لكل خانة: كل عمود يأخذ ثلث المساحة بمحاذاة يسار،
                    // فلا يضيق عمود «followers» ويُنقص منه رقم.
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

            Spacer(Modifier.height(6.dp))

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
}

@Composable
private fun StatColumn(
    modifier: Modifier = Modifier,
    value: Int?,
    label: String,
    loading: Boolean,
    valueSize: TextUnit,
    labelSize: TextUnit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // heightIn (وليس height) حتى لا يُقص أي رقم أو شريط تحميل
        Box(
            modifier = Modifier.heightIn(min = (valueSize.value * 1.3f).dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (loading) {
                ShimmerBar(width = 42.dp, height = 15.dp)
            } else {
                RollingNumber(value = value, fontSize = valueSize)
            }
        }
        Text(
            text = label,
            color = TextPrimary,
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
            .clip(RoundedCornerShape(20.dp))
            .background(Bubble)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = NOTE_TEXT,
            color = BubbleText,
            fontSize = 12.sp,
            lineHeight = 15.sp,
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
