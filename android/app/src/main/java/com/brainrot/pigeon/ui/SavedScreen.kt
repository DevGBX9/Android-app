package com.brainrot.pigeon.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.brainrot.pigeon.AppContainer
import com.brainrot.pigeon.R
import com.brainrot.pigeon.data.AvatarLoader
import com.brainrot.pigeon.data.PublishSchedule
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.ScheduleSlots
import com.brainrot.pigeon.ui.theme.Accent
import com.brainrot.pigeon.ui.theme.Outline
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import com.brainrot.pigeon.ui.util.openExternal
import com.brainrot.pigeon.ui.util.toast

private val CellTop = Color(0xFF1C232B)
private val CellBottom = Color(0xFF0F141A)

// نصف قطر الريل: 9:16 مثل تبويب الريلز في إنستغرام.
private const val REEL_ASPECT = 9f / 16f
private const val GRID_COLUMNS = 3

/**
 * صفحة الريلز: شريط أيقونة الشبكة مثل إنستغرام ثم خانات ريلز قائمة (9:16)
 * بصور غلاف حقيقية من إنستغرام. الضغط يفتح الريل، والضغط المطوّل يعرض
 * زر تحميل الريل المخزّن على Cloudflare مع نسبة 0..100.
 */
@Composable
fun ReelsRoute(container: AppContainer) {
    val viewModel: SavedViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SavedViewModel(container.saved) }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val deleteError by viewModel.deleteError.collectAsStateWithLifecycle()
    val images = remember { container.reelImages }
    val context = LocalContext.current
    var expandedId by remember { mutableStateOf<String?>(null) }

    // سبب فشل التحميل يظهر كرسالة بدل البقاء على 0% أو علامة تعجّب صامتة.
    LaunchedEffect(downloads) {
        val failure = downloads.entries.firstOrNull { it.value.error != null }
            ?: return@LaunchedEffect
        toast(context, failure.value.error ?: "the download failed")
        viewModel.dismissDownload(failure.key)
    }

    // سبب فشل الحذف من الخادم (مثلاً: الريل يُنشر الآن).
    LaunchedEffect(deleteError) {
        val message = deleteError ?: return@LaunchedEffect
        toast(context, message)
        viewModel.dismissDeleteError()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ReelsHeader()
        HorizontalDivider(color = Outline, thickness = 1.dp)

        if (items.isEmpty()) {
            EmptyReels(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.id }, contentType = { "reel" }) { item ->
                    ReelCell(
                        item = item,
                        images = images,
                        download = downloads[item.id],
                        expanded = expandedId == item.id,
                        onClick = { openReel(context, item) },
                        onLongClick = { expandedId = item.id },
                        onDismiss = { expandedId = null },
                        onDownload = {
                            expandedId = null
                            viewModel.download(item)
                        },
                        onDelete = {
                            expandedId = null
                            viewModel.delete(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelsHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GridGlyph(color = TextPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier
                    .width(54.dp)
                    .height(1.dp)
                    .background(TextPrimary),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReelCell(
    item: SavedItem,
    images: AvatarLoader,
    download: DownloadUi?,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    // الحذف لا رجعة فيه: ضغطة أولى تسأل، والثانية تحذف من Cloudflare وFirebase.
    var confirmingDelete by remember(expanded) { mutableStateOf(false) }

    val thumbnail by produceState<ImageBitmap?>(initialValue = null, item.thumbnail) {
        value = item.thumbnail
            ?.takeIf { it.isNotBlank() }
            ?.let { url -> images.load(url, targetSize = 360) }
    }

    val bitmap = thumbnail
    val processing = item.archiveState == "queued" ||
        item.archiveState == "downloading" ||
        item.archiveState == "storing"
    val busy = download?.running == true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(REEL_ASPECT)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(CellTop, CellBottom)))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = reelLabel(item),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // تعتيم سفلي حتى يبقى كود الريل واضحاً فوق الصورة.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.62f),
                            ),
                        ),
                    ),
            )
        } else {
            ReelGlyph(
                color = TextMuted.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
            )
        }

        if (item.registered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.reels_registered),
                    tint = Accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // تم التحميل على الجهاز.
        if (download?.done == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                DownloadGlyph(color = Color.White, modifier = Modifier.size(11.dp))
            }
        }

        if (download?.error != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "!", color = Color(0xFFFF6B6B), fontSize = 11.sp)
            }
        }

        Text(
            text = reelLabel(item),
            color = if (bitmap != null) Color.White else TextMuted,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, end = 30.dp, top = 5.dp, bottom = 5.dp),
        )

        item.schedule?.let { schedule ->
            ScheduleBadge(
                schedule = schedule,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp),
            )
        }

        // نسبة معالجة الريل على Cloudflare (0..100) قبل أن يصير قابلاً للتحميل.
        if (processing && !busy) {
            ProcessingBar(
                progress = item.archiveProgress,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 5.dp, end = 5.dp, bottom = 19.dp),
            )
        }

        // نزيل التحميل من الخادم إلى الجهاز: النسبة فوق البطاقة.
        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.66f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = "${download?.percent ?: 0}%",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.26f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(((download?.percent ?: 0).coerceIn(0, 100)) / 100f)
                                .fillMaxHeight()
                                .background(Accent),
                        )
                    }
                }
            }
        }

        // ضغطة مطوّلة: زر تحميل الريل المخزّن.
        if (expanded && !busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    item.schedule?.let { schedule ->
                        Text(
                            text = when (schedule.state) {
                                ScheduleSlots.STATE_PUBLISHED ->
                                    stringResource(R.string.schedule_published)

                                ScheduleSlots.STATE_FAILED ->
                                    stringResource(R.string.schedule_failed)

                                else -> ScheduleSlots.formatShort(schedule.at)
                            },
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (isArchivable(item)) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .clickable(onClick = onDownload)
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DownloadGlyph(color = Color.Black, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.reel_download),
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Text(
                        text = if (confirmingDelete) {
                            stringResource(R.string.reel_delete_confirm)
                        } else {
                            stringResource(R.string.reel_delete)
                        },
                        color = Color(0xFFFF6B6B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                if (confirmingDelete) onDelete() else confirmingDelete = true
                            }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    )

                    Text(
                        text = stringResource(R.string.action_close),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** شارة صغيرة أسفل الخانة: ساعة للمجدول، صح للناشر، تعجّب للفاشل. */
@Composable
private fun ScheduleBadge(schedule: PublishSchedule, modifier: Modifier = Modifier) {
    val published = schedule.state == ScheduleSlots.STATE_PUBLISHED
    val failed = schedule.state == ScheduleSlots.STATE_FAILED

    Box(
        modifier = modifier
            .size(19.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            published -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.schedule_published),
                tint = Accent,
                modifier = Modifier.size(12.dp),
            )

            failed -> Text(
                text = "!",
                color = Color(0xFFFF6B6B),
                fontSize = 11.sp,
            )

            else -> ClockGlyph(
                color = Color.White,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun ProcessingBar(progress: Int, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0, 100)

    Column(modifier.fillMaxWidth()) {
        Text(
            text = "$clamped%",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 8.sp,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(clamped / 100f)
                    .fillMaxHeight()
                    .background(Accent),
            )
        }
    }
}

@Composable
private fun EmptyReels(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ReelGlyph(color = TextMuted, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.reels_empty_title),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(R.string.reels_empty_body),
            color = TextMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** أيقونة شبكة ٣×٣ مرسومة، حتى لا نحتاج مكتبة أيقونات إضافية. */
@Composable
private fun GridGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / 3f
        val gap = cell * 0.16f

        for (row in 0..2) {
            for (column in 0..2) {
                drawRect(
                    color = color,
                    topLeft = Offset(column * cell + gap / 2f, row * cell + gap / 2f),
                    size = Size(cell - gap, cell - gap),
                )
            }
        }
    }
}

/** أيقونة ريل: مستطيل بزوايا دائرية بداخله مثلث تشغيل. */
@Composable
fun ReelGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension
        val stroke = side * 0.08f

        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(side * 0.24f),
            style = Stroke(stroke),
        )

        val play = Path().apply {
            moveTo(side * 0.38f, side * 0.30f)
            lineTo(side * 0.72f, side * 0.50f)
            lineTo(side * 0.38f, side * 0.70f)
            close()
        }

        drawPath(play, color)
    }
}

/** سهم نزول فوق خط: أيقونة تحميل مرسومة بدون مكتبات. */
@Composable
fun DownloadGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.13f

        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.08f),
            end = Offset(w * 0.5f, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        val arrow = Path().apply {
            moveTo(w * 0.24f, h * 0.42f)
            lineTo(w * 0.5f, h * 0.70f)
            lineTo(w * 0.76f, h * 0.42f)
        }
        drawPath(
            arrow,
            color = color,
            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        drawLine(
            color = color,
            start = Offset(w * 0.14f, h * 0.92f),
            end = Offset(w * 0.86f, h * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** ساعة بسيطة مرسومة: دائرة وعقربان، بلا مكتبة أيقونات إضافية. */
@Composable
private fun ClockGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension
        val stroke = side * 0.1f

        drawCircle(
            color = color,
            radius = side * 0.42f,
            style = Stroke(stroke),
        )
        drawLine(
            color = color,
            start = Offset(side * 0.5f, side * 0.5f),
            end = Offset(side * 0.5f, side * 0.27f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(side * 0.5f, side * 0.5f),
            end = Offset(side * 0.69f, side * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun reelLabel(item: SavedItem): String {
    val code = item.url
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }

    return code ?: item.title?.takeIf { it.isNotBlank() } ?: item.platform
}

private fun isArchivable(item: SavedItem): Boolean {
    val link = item.url
    if (link != null) {
        if (link.contains("instagram.com")) return true
        if (link.contains("tiktok.com")) {
            return link.contains("/video/") ||
                link.contains("vm.tiktok.com") ||
                link.contains("vt.tiktok.com") ||
                link.contains("/t/")
        }
    }
    return item.platform == "instagram" || item.platform == "tiktok"
}

private fun openReel(context: Context, item: SavedItem) {
    val url = item.url ?: item.remoteUrl
    if (url == null) {
        toast(context, context.getString(R.string.saved_no_content))
        return
    }
    if (!openExternal(context, url)) {
        toast(context, context.getString(R.string.saved_no_app))
    }
}
