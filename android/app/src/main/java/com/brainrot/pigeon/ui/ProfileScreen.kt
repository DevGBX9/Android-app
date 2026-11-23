package com.brainrot.pigeon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.data.PROFILE_PAGE_URL
import com.brainrot.pigeon.ui.components.ProfileCard
import com.brainrot.pigeon.ui.theme.TextPrimary
import com.brainrot.pigeon.ui.util.openExternal

@Composable
fun ProfileRoute(container: AppContainer) {
    val viewModel: ProfileViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ProfileViewModel(container.profile, container.avatars)
            }
        },
    )

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 430.dp),
        ) {
            ProfileCard(
                profile = profile,
                avatar = avatar,
                onAvatarLanded = viewModel::onAvatarLanded,
                onAvatarClick = { openExternal(context, PROFILE_PAGE_URL) },
            )

            Text(
                text = profile.bio.ifBlank { " " },
                color = TextPrimary,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
