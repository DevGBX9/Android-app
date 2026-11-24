package com.brainrot.pigeon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.PagerSnapDistance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.ui.theme.Background
import kotlinx.coroutines.launch

enum class AppTab {
    PROFILE,
    SAVED,
}

private const val PROFILE_PAGE = 0
private const val GRID_PAGE = 1
private const val PAGE_COUNT = 2

/**
 * صفحتان عموديتان: الحساب فوق وشبكة الريلز تحت. السحب للأعلى يرفع الحساب
 * وينزل الشبكة، والسحب للأسفل (أو زر الرجوع) يرجّع الحساب.
 *
 * نعتمد على ثلاثة أشياء ليبقى السحب ناعماً:
 *  - الصفحتان تُجهَّزان مسبقاً (`beyondViewportPageCount = 1`) فما يصير بناء
 *    الشبكة وسط الإصبع (وهذا كان سبب التقطيع).
 *  - سلوك سحب/إفلات واحد بحركة tween ثابتة بدل النابض، وعتبة تبديل منخفضة،
 *    فما يرجّع الصفحة للوراء بشكل غريب.
 *  - تحديث الحساب الدوري يتوقف والشبكة ظاهرة، ما في إعادة رسم فوق السحب.
 */
@Composable
fun PigeonRoot(
    container: AppContainer,
    requestedTab: AppTab,
) {
    val pagerState = rememberPagerState(
        initialPage = if (requestedTab == AppTab.SAVED) GRID_PAGE else PROFILE_PAGE,
        pageCount = { PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedTab) {
        val target = if (requestedTab == AppTab.SAVED) GRID_PAGE else PROFILE_PAGE
        if (pagerState.settledPage != target) pagerState.animateScrollToPage(target)
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
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                snapPositionalThreshold = 0.16f,
                snapAnimationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                ),
            ),
        ) { page ->
            when (page) {
                GRID_PAGE -> ReelsRoute(container)
                else -> ProfileRoute(
                    container = container,
                    active = pagerState.settledPage == PROFILE_PAGE,
                )
            }
        }
    }
}
