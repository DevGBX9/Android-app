package com.brainrot.pigeon.ui

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.ui.theme.Bubble
import com.brainrot.pigeon.ui.theme.CardSurface
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import com.brainrot.pigeon.ui.util.openExternal
import com.brainrot.pigeon.ui.util.openUri
import com.brainrot.pigeon.ui.util.shareFile
import com.brainrot.pigeon.ui.util.shareText
import com.brainrot.pigeon.ui.util.toast
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val savedTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("ar"))

@Composable
fun SavedRoute(container: AppContainer) {
    val viewModel: SavedViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SavedViewModel(container.saved, container.settings, container.uploader)
            }
        },
    )

    val items by viewModel.items.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.saved_empty_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.saved_empty_body),
                    color = TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.saved_count, items.size),
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }

                items(items, key = { it.id }) { item ->
                    SavedRow(
                        item = item,
                        busy = busyId == item.id,
                        onOpen = { openSaved(context, item) },
                        onShare = { shareSaved(context, item) },
                        onUpload = { viewModel.upload(item) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }
}

@Composable
private fun SavedRow(
    item: SavedItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformChip(platform = item.platform)
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(item.createdAt),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }

            Text(
                text = item.title ?: item.url ?: item.fileName
                ?: stringResource(R.string.saved_link_only),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (item.remoteUrl != null) {
                Text(
                    text = stringResource(R.string.saved_uploaded),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SavedAction(stringResource(R.string.saved_open), onOpen)
                SavedAction(stringResource(R.string.saved_share), onShare)

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .size(16.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    SavedAction(stringResource(R.string.saved_upload), onUpload)
                }

                SavedAction(stringResource(R.string.saved_delete), onDelete)
            }
        }
    }
}

@Composable
private fun PlatformChip(platform: String) {
    val label = when (platform) {
        "instagram" -> "Instagram"
        "tiktok" -> "TikTok"
        "video", "file" -> stringResource(R.string.saved_file)
        else -> stringResource(R.string.saved_link_only)
    }

    Text(
        text = label,
        color = TextPrimary,
        fontSize = 11.sp,
        modifier = Modifier
            .background(Bubble, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun SavedAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = TextPrimary, fontSize = 12.sp)
    }
}

private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(savedTimeFormatter)
    }.getOrDefault("")
}

private fun openSaved(context: Context, item: SavedItem) {
    val file = item.filePath?.let(::File)

    if (file != null && file.isFile) {
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()?.let { uri ->
            if (openUri(context, uri)) return
        }
    }

    val url = item.url ?: item.remoteUrl
    if (url != null) {
        if (!openExternal(context, url)) {
            toast(context, context.getString(R.string.saved_no_app))
        }
    } else {
        toast(context, context.getString(R.string.saved_no_content))
    }
}

private fun shareSaved(context: Context, item: SavedItem) {
    val file = item.filePath?.let(::File)

    if (file != null && file.isFile) {
        shareFile(context, file, item.mimeType)
    } else {
        item.url?.let { shareText(context, it) }
    }
}
