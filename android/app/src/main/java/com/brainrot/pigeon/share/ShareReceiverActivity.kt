package com.brainrot.pigeon.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.brainrot.pigeon.MainActivity
import com.brainrot.pigeon.PigeonApp
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.AppTab
import com.brainrot.pigeon.ui.ReelGlyph
import com.brainrot.pigeon.ui.theme.Background
import com.brainrot.pigeon.ui.theme.CardSurface
import com.brainrot.pigeon.ui.theme.Outline
import com.brainrot.pigeon.ui.theme.PigeonTheme
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import kotlinx.coroutines.launch

private val InstagramGradient = listOf(Color(0xFFF58529), Color(0xFFE1306C), Color(0xFF8134AF))

private sealed interface ShareState {
    data object Working : ShareState
    data class Added(val detail: String) : ShareState
    data object Empty : ShareState
}

/**
 * شاشة شفافة فوق التطبيق الذي شارك الريل (إنستغرام مثلاً): تحفظ الريل وتعرض
 * بوب-أب في منتصف الشاشة بزرّي View وClose، فلا ينتقل المستخدم خارج إنستغرام
 * إلا إذا ضغط View.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var state by mutableStateOf<ShareState>(ShareState.Working)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PigeonApp).container
        val incoming = intent

        lifecycleScope.launch {
            val added = ShareIntake(this@ShareReceiverActivity, container.saved).handle(incoming)
            state = when {
                added.isEmpty() -> ShareState.Empty
                added.size == 1 -> ShareState.Added(added.first())
                else -> ShareState.Added(getString(R.string.share_dialog_many, added.size))
            }
        }

        setContent {
            PigeonTheme {
                ShareOverlay(
                    state = state,
                    onView = { openApp() },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TAB, AppTab.SAVED.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
        )
        finish()
    }
}

@Composable
private fun ShareOverlay(
    state: ShareState,
    onView: () -> Unit,
    onClose: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state != ShareState.Working,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(170)) + scaleIn(tween(230), initialScale = 0.9f),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = CardSurface,
                shadowElevation = 24.dp,
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth()
                    .widthIn(max = 340.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(InstagramGradient)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ReelGlyph(color = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Spacer(Modifier.height(14.dp))

                    when (state) {
                        ShareState.Working -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = TextMuted,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.share_dialog_saving),
                                color = TextMuted,
                                fontSize = 13.sp,
                            )
                        }

                        is ShareState.Added -> {
                            Text(
                                text = stringResource(R.string.share_dialog_title),
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = state.detail,
                                color = TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(18.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = onView,
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TextPrimary,
                                        contentColor = Background,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_view),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }

                                OutlinedButton(
                                    onClick = onClose,
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, Outline),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = TextPrimary,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_close),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        ShareState.Empty -> {
                            Text(
                                text = stringResource(R.string.share_dialog_failed_title),
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = stringResource(R.string.share_dialog_failed_body),
                                color = TextMuted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(18.dp))
                            OutlinedButton(
                                onClick = onClose,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, Outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = TextPrimary,
                                ),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.action_close),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
