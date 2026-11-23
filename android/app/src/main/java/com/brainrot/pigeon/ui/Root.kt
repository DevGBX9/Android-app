package com.brainrot.pigeon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.theme.BarSurface
import com.brainrot.pigeon.ui.theme.Background
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary

enum class AppTab(val labelRes: Int, val icon: ImageVector) {
    PROFILE(R.string.tab_profile, Icons.Filled.Home),
    SAVED(R.string.tab_saved, Icons.Filled.List),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun PigeonRoot(container: AppContainer, requestedTab: AppTab) {
    var tab by rememberSaveable { mutableStateOf(requestedTab) }

    LaunchedEffect(requestedTab) { tab = requestedTab }

    Scaffold(
        containerColor = Background,
        bottomBar = {
            NavigationBar(containerColor = BarSurface, tonalElevation = 0.dp) {
                AppTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(imageVector = entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.labelRes), fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = Background,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                AppTab.PROFILE -> ProfileRoute(container)
                AppTab.SAVED -> SavedRoute(container)
                AppTab.SETTINGS -> SettingsRoute(container)
            }
        }
    }
}
