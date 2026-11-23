package com.brainrot.pigeon.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun ProfileCard(
    profile: Profile,
    avatar: AvatarUiState,
    onAvatarLanded: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp
        val tight = maxWidth < 340.dp

        val avatarSize = if (compact) 78.dp else 88.dp
        val valueSize = when {
            tight -> 12.sp
            compact -> 15.sp
            else -> 17.sp
        }
        val statGap = when {
            tight -> 10.dp
            compact -> 20.dp
            else -> 30.dp
        }
        val nameSize = if (compact) 15.sp else 17.sp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(modifier = Modifier.width(avatarSize + 6.dp)) {
                NoteBubble(
                    width = avatarSize + 6.dp,
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
                    .padding(top = if (compact) 30.dp else 34.dp),
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

                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(statGap),
                ) {
                    StatColumn(value = profile.posts, label = "posts", valueSize = valueSize)
                    StatColumn(value = profile.followers, label = "followers", valueSize = valueSize)
                    StatColumn(value = profile.following, label = "following", valueSize = valueSize)
                }
            }
        }
    }
}

@Composable
private fun StatColumn(value: Int?, label: String, valueSize: TextUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RollingNumber(value = value, fontSize = valueSize)
        Text(text = label, color = TextPrimary, fontSize = valueSize, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NoteBubble(width: Dp, minHeight: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(Bubble)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = NOTE_TEXT,
            color = BubbleText,
            fontSize = 15.sp,
            lineHeight = 19.sp,
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
