package com.brainrot.pigeon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.theme.Background
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary

enum class AppTab(val labelRes: Int) {
    PROFILE(R.string.tab_profile),
    SAVED(R.string.tab_saved),
    SETTINGS(R.string.tab_settings),
}

/**
 * بدون شريط تنقل سفلي: شاشة الحساب هي الأساس، وأزرار صغيرة أعلى اليمين
 * للمحفوظات والإعدادات، وسهم رجوع داخل الشاشتين.
 */
@Composable
fun PigeonRoot(container: AppContainer, requestedTab: AppTab) {
    var tab by rememberSaveable { mutableStateOf(requestedTab) }

    LaunchedEffect(requestedTab) { tab = requestedTab }

    // نرجّع التحديث عند العودة للحساب (مثلاً بعد حفظ توكن جديد).
    LaunchedEffect(tab) {
        if (tab == AppTab.PROFILE) {
            container.profile.reset()
            runCatching { container.profile.refresh(minGapMs = 0L) }
        }
    }

    BackHandler(enabled = tab != AppTab.PROFILE) { tab = AppTab.PROFILE }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopActions(
                current = tab,
                onBack = { tab = AppTab.PROFILE },
                onOpen = { tab = it },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                AppTab.PROFILE -> ProfileRoute(
                    container = container,
                    onOpenSettings = { tab = AppTab.SETTINGS },
                )
                AppTab.SAVED -> SavedRoute(container)
                AppTab.SETTINGS -> SettingsRoute(container)
            }
        }
    }
}

@Composable
private fun TopActions(
    current: AppTab,
    onBack: () -> Unit,
    onOpen: (AppTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current == AppTab.PROFILE) {
            Spacer(Modifier.width(4.dp))
        } else {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = TextMuted,
                )
            }
            Text(
                text = stringResource(current.labelRes),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.weight(1f))

        if (current == AppTab.PROFILE) {
            IconButton(onClick = { onOpen(AppTab.SAVED) }) {
                Icon(
                    imageVector = Icons.Filled.List,
                    contentDescription = stringResource(R.string.tab_saved),
                    tint = TextMuted,
                )
            }
            IconButton(onClick = { onOpen(AppTab.SETTINGS) }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.tab_settings),
                    tint = TextMuted,
                )
            }
        }
    }
}
