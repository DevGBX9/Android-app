package com.brainrot.pigeon.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.brainrot.pigeon.data.SchedulePlanner
import com.brainrot.pigeon.data.SchedulePolicy
import com.brainrot.pigeon.data.ScheduleSlots
import com.brainrot.pigeon.data.isPublishing
import com.brainrot.pigeon.ui.theme.Accent
import com.brainrot.pigeon.ui.theme.Outline
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import com.brainrot.pigeon.ui.util.openExternal
import com.brainrot.pigeon.ui.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CellTop = Color(0xFF1C232B)
private val CellBottom = Color(0xFF0F141A)
private val DeleteRed = Color(0xFFFF4D4D)

// نصف قطر الريل: 9:16 مثل تبويب الريلز في إنستغرام.
private const val REEL_ASPECT = 9f / 16f
private const val GRID_COLUMNS = 3

/**
 * صفحة الريلز: شريط أيقونة الشبكة مثل إنستغرام ثم خانات ريلز قائمة (9:16)
 * بصور غلاف حقيقية من إنستغرام. الضغط يفتح الريل، والضغط المطوّل يفتح قائمة
 * سفلية بأنماط إنستغرام (تحميل، تغيير الموعد، حذف). الخانة التي تُنشر أو
 * تُحذف تعرض علامة صح أو سلة حمراء بأنيميشن ثم تختفي.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val scheduleError by viewModel.scheduleError.collectAsStateWithLifecycle()
    val exits by viewModel.exits.collectAsStateWithLifecycle()
    val images = remember { container.reelImages }
    val context = LocalContext.current

    // ساعة واحدة للشبكة كلها، وهي ساعة الخادم: التطبيق لا يحسب موعداً بنفسه.
    val now by viewModel.serverNow.collectAsStateWithLifecycle()

    // الخانة التي فُتحت لها القائمة السفلية، والريل الذي يُعدَّل موعده.
    var sheetFor by remember { mutableStateOf<SavedItem?>(null) }
    var editingFor by remember { mutableStateOf<SavedItem?>(null) }

    // نقرأ العنصر من القائمة الحيّة: لو نُشر أو اختفى أثناء فتح القائمة تُغلق.
    val sheetItem = sheetFor?.let { target -> items.firstOrNull { it.id == target.id } }
    val editItem = editingFor?.let { target -> items.firstOrNull { it.id == target.id } }

    LaunchedEffect(sheetItem, editItem) {
        if (sheetItem == null) sheetFor = null
        if (editItem == null) editingFor = null
    }

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

    LaunchedEffect(scheduleError) {
        val message = scheduleError ?: return@LaunchedEffect
        toast(context, message)
        viewModel.dismissScheduleError()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ReelsHeader()
        HorizontalDivider(color = Outline, thickness = 1.dp)

        // الانتقال بين "شارك أول ريل" والشبكة يصير بتلاشي ناعم بدل قفزة.
        Crossfade(
            targetState = items.isEmpty(),
            modifier = Modifier.weight(1f),
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            label = "reels-content",
        ) { empty ->
            if (empty) {
                EmptyReels(Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> "reel" },
                    ) { index, item ->
                        ReelCell(
                            // ظهور الخانة الجديدة وانزلاق الباقي عند الحذف.
                            modifier = Modifier.animateItem(),
                            item = item,
                            number = index + 1,
                            now = now,
                            // بقية الشبكة تبهت والخانة المفتوحة إعداداتها تبرز.
                            dimmed = sheetItem != null && sheetItem?.id != item.id,
                            focused = sheetItem?.id == item.id,
                            images = images,
                            download = downloads[item.id],
                            exit = exits[item.id],
                            onClick = { openReel(context, item) },
                            onLongClick = { sheetFor = item },
                        )
                    }
                }
            }
        }
    }

    // قائمة إنستغرام السفلية: تنزيل، تغيير تاريخ الجدولة، حذف.
    sheetItem?.let { target ->
        ReelSheet(
            item = target,
            number = items.indexOfFirst { it.id == target.id } + 1,
            download = downloads[target.id],
            onDismiss = { sheetFor = null },
            onDownload = {
                sheetFor = null
                viewModel.download(target)
            },
            onReschedule = {
                sheetFor = null
                editingFor = target
            },
            onDelete = {
                sheetFor = null
                viewModel.delete(target)
            },
        )
    }

    editItem?.let { target ->
        ScheduleEditDialog(
            initialAt = target.schedule?.at?.takeIf { it > System.currentTimeMillis() }
                ?: ScheduleSlots.at(
                    SchedulePlanner.defaultManualDate(
                        ScheduleSlots.PUBLISH_HOUR,
                        ScheduleSlots.PUBLISH_MINUTE,
                    ),
                    ScheduleSlots.PUBLISH_HOUR,
                    ScheduleSlots.PUBLISH_MINUTE,
                ),
            onDismiss = { editingFor = null },
            onConfirm = { at ->
                editingFor = null
                viewModel.reschedule(target, at)
            },
        )
    }
}

/**
 * قائمة سفلية مثل إنستغرام: تُفتح من الضغطة المطوّلة، صفوفها أيقونة ونص،
 * وتُغلق بالضغط على الخلفية الفارغة أو بالسحب للأسفل.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelSheet(
    item: SavedItem,
    number: Int,
    download: DownloadUi?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onReschedule: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val downloadPercent = download?.percent ?: 0

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        ) {
            // رقم الريل: تعرف بالضبط أي ريل تعدّل إعداداته.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "#$number",
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.reel_number, number),
                        color = scheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    item.schedule?.at?.takeIf { it > 0L }?.let { at ->
                        Text(
                            text = ScheduleSlots.formatShort(at),
                            color = scheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (isArchivable(item)) {
                SheetRow(
                    label = if (download?.running == true) {
                        stringResource(R.string.reel_downloading, downloadPercent)
                    } else {
                        stringResource(R.string.reel_download)
                    },
                    tint = scheme.onSurface,
                    onClick = onDownload,
                ) {
                    DownloadGlyph(color = scheme.onSurface, modifier = Modifier.size(22.dp))
                }
            }

            if (SchedulePolicy.canSchedule(item)) {
                SheetRow(
                    label = if (item.schedule == null) {
                        stringResource(R.string.reel_schedule)
                    } else {
                        stringResource(R.string.schedule_change)
                    },
                    tint = scheme.onSurface,
                    onClick = onReschedule,
                ) {
                    CalendarGlyph(color = scheme.onSurface, modifier = Modifier.size(22.dp))
                }
            }

            // الحذف لا رجعة فيه: ضغطة أولى تسأل، والثانية تحذف.
            SheetRow(
                label = if (confirmingDelete) {
                    stringResource(R.string.reel_delete_confirm)
                } else {
                    stringResource(R.string.reel_delete)
                },
                tint = DeleteRed,
                onClick = {
                    if (confirmingDelete) onDelete() else confirmingDelete = true
                },
            ) {
                TrashGlyph(color = DeleteRed, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    tint: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(text = label, color = tint, fontSize = 15.sp)
    }
}

@Composable
private fun ReelsHeader() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.8f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "header-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "header-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha),
        ) {
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
    modifier: Modifier = Modifier,
    item: SavedItem,
    number: Int,
    now: Long,
    dimmed: Boolean,
    focused: Boolean,
    images: AvatarLoader,
    download: DownloadUi?,
    exit: ReelExitKind?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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

    // ظهور الغلاف بتلاشي بدل ما يقفز الصورة دفعة واحدة، وشريط التحميل يتقدّم بنعومة.
    val thumbAlpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "thumb-alpha",
    )
    val downloadFraction by animateFloatAsState(
        targetValue = ((download?.percent ?: 0).coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "download-fraction",
    )
    // تعتيم ناعم لبقية الشبكة، وإطار ملوّن حول الريل المفتوح.
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.30f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cell-dim",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(REEL_ASPECT)
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(CellTop, CellBottom)))
            .graphicsLayer(alpha = dimAlpha)
            .then(
                if (focused) {
                    Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = exit == null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = reelLabel(item),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = thumbAlpha),
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

        AnimatedVisibility(
            visible = item.registered,
            enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
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

        // رقم الريل في الشبكة، وأيقونات التحميل بجانبه حتى لا تتراكب.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReelNumberBadge(number = number)

            if (download?.done == true) {
                Box(
                    modifier = Modifier
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
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "!", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                }
            }
        }

        // معلومات الريل داخل إطاره: تاريخ الخادم، وحالته، والعدّاد من ساعته.
        val schedule = item.schedule
        val scheduledAt = schedule?.at?.takeIf { it > 0L }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, end = 26.dp, top = 5.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (scheduledAt != null && schedule != null) {
                Text(
                    text = ScheduleSlots.formatShort(scheduledAt),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // النص يتبع حالة الخادم: لا "publishing" من تقدير التطبيق.
                    text = when {
                        schedule.isPublishing ->
                            stringResource(R.string.reel_countdown_now)

                        schedule.state == ScheduleSlots.STATE_PUBLISHED ->
                            stringResource(R.string.schedule_published)

                        schedule.state == ScheduleSlots.STATE_FAILED ||
                            schedule.state == ScheduleSlots.STATE_UNVERIFIED ->
                            stringResource(R.string.schedule_failed)

                        scheduledAt > now -> stringResource(
                            R.string.reel_countdown,
                            ScheduleSlots.formatCountdown(scheduledAt - now),
                        )

                        else -> stringResource(R.string.reel_state_waiting)
                    },
                    color = Accent,
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = reelLabel(item),
                color = if (bitmap != null) Color.White.copy(alpha = 0.78f) else TextMuted,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = schedule != null,
            enter = fadeIn(tween(220)) +
                scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.7f),
            exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp),
        ) {
            if (schedule != null) {
                ScheduleBadge(schedule = schedule)
            }
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
        AnimatedVisibility(
            visible = busy,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
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
                                .fillMaxWidth(downloadFraction)
                                .fillMaxHeight()
                                .background(Accent),
                        )
                    }
                }
            }
        }

        // علامة النشر أو الحذف: تظهر بأنيميشن ثم تختفي الخانة نفسها.
        if (exit != null) {
            ReelExitOverlay(kind = exit)
        }
    }
}

/** علامة خروج الخانة: صح أخضر وردي صاعد بعد النشر، أو سلة حمراء بعد الحذف. */
@Composable
private fun ReelExitOverlay(kind: ReelExitKind, modifier: Modifier = Modifier) {
    val published = kind == ReelExitKind.Published
    val tint = if (published) Accent else DeleteRed
    val scrim = remember { Animatable(0f) }
    val scale = remember { Animatable(0.5f) }
    val stroke = remember { Animatable(0f) }

    LaunchedEffect(kind) {
        launch { scrim.animateTo(1f, tween(170, easing = FastOutSlowInEasing)) }
        launch { scale.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        if (published) {
            launch {
                stroke.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(340, delayMillis = 90, easing = FastOutSlowInEasing),
                )
            }
        }
        delay(860)
        scrim.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f * scrim.value)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer(scaleX = scale.value, scaleY = scale.value, alpha = scrim.value)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                if (published) {
                    CheckGlyph(
                        progress = stroke.value,
                        color = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                } else {
                    TrashGlyph(color = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(
                    if (published) R.string.schedule_published else R.string.reel_deleted,
                ),
                color = Color.White.copy(alpha = scrim.value),
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** صح يُرسم تدريجياً: الجزء الأول ثم الثاني، مثل علامة تُكتب. */
@Composable
private fun CheckGlyph(progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.18f
        val start = Offset(w * 0.12f, h * 0.54f)
        val corner = Offset(w * 0.40f, h * 0.82f)
        val end = Offset(w * 0.90f, h * 0.20f)

        val first = (progress / 0.45f).coerceIn(0f, 1f)
        val second = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)

        drawLine(
            color = color,
            start = start,
            end = Offset(
                start.x + (corner.x - start.x) * first,
                start.y + (corner.y - start.y) * first,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (second > 0f) {
            drawLine(
                color = color,
                start = corner,
                end = Offset(
                    corner.x + (end.x - corner.x) * second,
                    corner.y + (end.y - corner.y) * second,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** سلة مهملات مرسومة بنفس أسلوب بقية الأيقونات، بلا مكتبة إضافية. */
@Composable
fun TrashGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.11f

        // الغطاء والمقبض.
        drawLine(
            color = color,
            start = Offset(s * 0.14f, s * 0.28f),
            end = Offset(s * 0.86f, s * 0.28f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.40f, s * 0.28f),
            end = Offset(s * 0.40f, s * 0.14f),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.40f, s * 0.14f),
            end = Offset(s * 0.60f, s * 0.14f),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.60f, s * 0.14f),
            end = Offset(s * 0.60f, s * 0.28f),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round,
        )

        // جسم السلة.
        val body = Path().apply {
            moveTo(s * 0.24f, s * 0.36f)
            lineTo(s * 0.30f, s * 0.86f)
            lineTo(s * 0.70f, s * 0.86f)
            lineTo(s * 0.76f, s * 0.36f)
        }
        drawPath(
            body,
            color = color,
            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        drawLine(
            color = color,
            start = Offset(s * 0.42f, s * 0.46f),
            end = Offset(s * 0.44f, s * 0.74f),
            strokeWidth = stroke * 0.7f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.58f, s * 0.46f),
            end = Offset(s * 0.56f, s * 0.74f),
            strokeWidth = stroke * 0.7f,
            cap = StrokeCap.Round,
        )
    }
}

/** تقويم بسيط لصف "تغيير تاريخ الجدولة". */
@Composable
internal fun CalendarGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.09f

        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.12f, s * 0.20f),
            size = Size(s * 0.76f, s * 0.68f),
            cornerRadius = CornerRadius(s * 0.14f),
            style = Stroke(stroke),
        )
        drawLine(
            color = color,
            start = Offset(s * 0.12f, s * 0.40f),
            end = Offset(s * 0.88f, s * 0.40f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.34f, s * 0.10f),
            end = Offset(s * 0.34f, s * 0.28f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(s * 0.66f, s * 0.10f),
            end = Offset(s * 0.66f, s * 0.28f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** رقم الريل (#3) داخل شارة صغيرة أعلى الخانة، حتى تعرف أي ريل تعدّله. */
@Composable
private fun ReelNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "#$number",
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
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
    val fraction by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "archive-fraction",
    )

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
                    .fillMaxWidth(fraction)
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
