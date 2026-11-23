package com.brainrot.pigeon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.theme.Accent
import com.brainrot.pigeon.ui.theme.Background
import com.brainrot.pigeon.ui.theme.CardSurface
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppTab {
    PROFILE,
    SAVED,
}

private const val PROFILE_PAGE = 0
private const val GRID_PAGE = 1
private const val PAGE_COUNT = 2
private const val POPUP_VISIBLE_MS = 4_500L

/**
 * صفحتان عموديتان: صفحة الحساب فوق، وشبكة الريلز المجدولة تحتها.
 * سحب الشاشة للأعلى يرفع الحساب بإنميشن ويترك الشبكة، وسحب الشبكة للأسفل
 * (أو زر الرجوع) يرجّع الحساب.
 */
@Composable
fun PigeonRoot(
    container: AppContainer,
    requestedTab: AppTab,
    sharedReel: String?,
    onSharedReelShown: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = if (requestedTab == AppTab.SAVED) GRID_PAGE else PROFILE_PAGE,
        pageCount = { PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedTab) {
        val target = if (requestedTab == AppTab.SAVED) GRID_PAGE else PROFILE_PAGE
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }

    BackHandler(enabled = pagerState.currentPage != PROFILE_PAGE) {
        scope.launch { pagerState.animateScrollToPage(PROFILE_PAGE) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                GRID_PAGE -> ReelsRoute(container)
                else -> ProfileRoute(container)
            }
        }

        SharedReelPopup(
            reelTitle = sharedReel,
            onDismiss = onSharedReelShown,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** النافذة الطافية بعد المشاركة: تعرض اسم الريل وزر إغلاق، وتختفي وحدها. */
@Composable
private fun SharedReelPopup(
    reelTitle: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = remember { mutableStateOf("") }

    LaunchedEffect(reelTitle) {
        if (reelTitle == null) return@LaunchedEffect
        shown.value = reelTitle
        delay(POPUP_VISIBLE_MS)
        onDismiss()
    }

    AnimatedVisibility(
        visible = reelTitle != null,
        modifier = modifier,
        enter = slideInVertically(animationSpec = tween(280)) { -it } + fadeIn(tween(220)),
        exit = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(160)),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardSurface,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(AccentGradient)),
                    contentAlignment = Alignment.Center,
                ) {
                    ReelGlyph(color = Color.White, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(11.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reel_added_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = shown.value.ifBlank { stringResource(R.string.shared_reel_unknown) },
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private val AccentGradient = listOf(Color(0xFFF58529), Accent, Color(0xFF8134AF))
