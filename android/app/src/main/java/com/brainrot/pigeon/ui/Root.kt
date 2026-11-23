package com.brainrot.pigeon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
 * Two vertical pages: the profile above and the scheduled reels grid below.
 * Swiping up lifts the profile out with an animation and leaves the grid;
 * swiping the grid down (or back) brings the profile back.
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
    }
}
