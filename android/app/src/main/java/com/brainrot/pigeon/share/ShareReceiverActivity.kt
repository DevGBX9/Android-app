package com.brainrot.pigeon.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.lifecycle.lifecycleScope
import com.brainrot.pigeon.MainActivity
import com.brainrot.pigeon.PigeonApp
import com.brainrot.pigeon.R
import com.brainrot.pigeon.data.SavedItem
import com.brainrot.pigeon.data.SavedRepository
import com.brainrot.pigeon.data.SchedulePlan
import com.brainrot.pigeon.data.SchedulePlanner
import com.brainrot.pigeon.data.SchedulePolicy
import com.brainrot.pigeon.data.ScheduleSlots
import com.brainrot.pigeon.ui.AppTab
import com.brainrot.pigeon.ui.ReelGlyph
import com.brainrot.pigeon.ui.theme.PigeonTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

private val InstagramGradient = listOf(Color(0xFFF58529), Color(0xFFE1306C), Color(0xFF8134AF))

private sealed interface SharePhase {
    data object Working : SharePhase
    data object Empty : SharePhase
    data object Scheduling : SharePhase
    data object Saving : SharePhase
    data class Added(val detail: String) : SharePhase
}

private enum class ScheduleStep { Auto, Time, Date }

/** مفتاح المحتوى داخل النافذة: أي تغيير فيه يشغّل انتقالاً متحركاً. */
private data class ScreenKey(val phase: SharePhase, val step: ScheduleStep)

/**
 * شاشة شفافة فوق التطبيق الذي شارك الريل (إنستغرام مثلاً): تحضّر الريل أولاً،
 * تعرض خطوة اختيار وقت النشر ثم التاريخ (أول ريل فقط)، وبعد التأكيد تحفظه
 * وترفع موعده إلى Firebase لينشره Cloudflare في وقته.
 *
 * واجهة الجدولة بستايل Material You: منتقي وقت دائري (TimePicker) وتقويم
 * (DatePicker) من Material 3، مع انتقالات منزلقة بين الخطوات.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var phase by mutableStateOf<SharePhase>(SharePhase.Working)
    private var plan by mutableStateOf<SchedulePlan?>(null)
    private var step by mutableStateOf(ScheduleStep.Auto)
    private var hour by mutableStateOf(ScheduleSlots.PUBLISH_HOUR)
    private var minute by mutableStateOf(ScheduleSlots.PUBLISH_MINUTE)
    private var date by mutableStateOf<LocalDate?>(null)
    private var pending by mutableStateOf<List<SavedItem>>(emptyList())

    private val intake by lazy { ShareIntake(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PigeonApp).container
        val incoming = intent

        lifecycleScope.launch {
            val prepared = intake.prepare(incoming)
            if (prepared.isEmpty()) {
                phase = SharePhase.Empty
                return@launch
            }

            // الملفات المحلية لا يستطيع Cloudflare نشرها؛ تُحفظ فوراً كما قبل.
            if (prepared.none { SchedulePolicy.canSchedule(it) }) {
                container.saved.addAll(prepared)
                phase = SharePhase.Added(detailOf(prepared))
                return@launch
            }

            pending = prepared
            val schedulePlan = container.saved.schedulePlan()
            plan = schedulePlan
            when (schedulePlan) {
                SchedulePlan.Manual -> {
                    hour = ScheduleSlots.PUBLISH_HOUR
                    minute = ScheduleSlots.PUBLISH_MINUTE
                    date = SchedulePlanner.defaultManualDate(hour, minute)
                    step = ScheduleStep.Time
                }

                is SchedulePlan.Auto -> {
                    hour = ScheduleSlots.timeOf(schedulePlan.at).hour
                    minute = ScheduleSlots.timeOf(schedulePlan.at).minute
                    date = ScheduleSlots.dateOf(schedulePlan.at)
                    step = ScheduleStep.Auto
                }
            }
            phase = SharePhase.Scheduling
        }

        setContent {
            PigeonTheme {
                ShareOverlay(
                    phase = phase,
                    plan = plan,
                    step = step,
                    hour = hour,
                    minute = minute,
                    date = date,
                    onHour = { hour = it },
                    onMinute = { minute = it },
                    onDate = { date = it },
                    onNext = {
                        val chosen = date?.takeIf {
                            ScheduleSlots.at(it, hour, minute) > System.currentTimeMillis()
                        }
                        date = chosen ?: SchedulePlanner.defaultManualDate(hour, minute)
                        step = ScheduleStep.Date
                    },
                    onBack = { step = ScheduleStep.Time },
                    onReset = { step = ScheduleStep.Auto },
                    onEdit = { step = ScheduleStep.Time },
                    onConfirm = {
                        // الموعد الموجود في الحقول هو الموعد التلقائي ما لم يغيّره
                        // المستخدم، فنحفظ المختار سواء كان تلقائياً أو معدّلاً يدوياً.
                        val now = System.currentTimeMillis()
                        val chosen = date?.let { ScheduleSlots.at(it, hour, minute) }
                        if (chosen != null && chosen > now) {
                            save(chosen, container.saved)
                        } else {
                            // مرّ الوقت المختار أثناء الاختيار: نعيد لموعد صالح.
                            date = SchedulePlanner.defaultManualDate(hour, minute, now)
                        }
                    },
                    onCancel = {
                        intake.discard(pending)
                        finish()
                    },
                    onView = { openApp() },
                    onClose = {
                        if (phase == SharePhase.Scheduling) intake.discard(pending)
                        finish()
                    },
                )
            }
        }
    }

    private fun save(firstAt: Long, saved: SavedRepository) {
        val assigned = SchedulePlanner.assign(pending, firstAt)
        phase = SharePhase.Saving
        lifecycleScope.launch {
            saved.addAll(assigned)
            phase = SharePhase.Added(detailOf(assigned))
        }
    }

    private fun detailOf(items: List<SavedItem>): String {
        val scheduled = items.mapNotNull { it.schedule }
        return when {
            scheduled.size == 1 ->
                getString(R.string.schedule_added, ScheduleSlots.formatFull(scheduled.first().at))

            scheduled.size > 1 ->
                getString(
                    R.string.schedule_added_many,
                    scheduled.size,
                    ScheduleSlots.formatFull(scheduled.first().at),
                )

            items.size == 1 ->
                items.first().title ?: items.first().platform

            else ->
                getString(R.string.share_dialog_many, items.size)
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
    phase: SharePhase,
    plan: SchedulePlan?,
    step: ScheduleStep,
    hour: Int,
    minute: Int,
    date: LocalDate?,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    onDate: (LocalDate) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onView: () -> Unit,
    onClose: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val busy = phase == SharePhase.Working || phase == SharePhase.Saving
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val cardMaxHeight = maxHeight - 44.dp

        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(170)) +
                scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.9f),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                shadowElevation = 26.dp,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .widthIn(max = 400.dp)
                    .heightIn(max = cardMaxHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MarkBadge()

                    if (phase == SharePhase.Scheduling) {
                        Spacer(Modifier.height(10.dp))
                        StepDots(
                            index = when (step) {
                                ScheduleStep.Auto -> 0
                                ScheduleStep.Time -> 0
                                ScheduleStep.Date -> 1
                            },
                            count = 2,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    AnimatedContent(
                        targetState = ScreenKey(phase, step),
                        transitionSpec = {
                            val bothScheduling = initialState.phase is SharePhase.Scheduling &&
                                targetState.phase is SharePhase.Scheduling
                            if (bothScheduling) {
                                val forward = targetState.step.ordinal > initialState.step.ordinal
                                (
                                    slideInHorizontally(
                                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                                        initialOffsetX = { width -> if (forward) width / 2 else -width / 2 },
                                    ) + fadeIn(tween(200))
                                    ).togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                                        targetOffsetX = { width -> if (forward) -width / 2 else width / 2 },
                                    ) + fadeOut(tween(130)),
                                )
                            } else {
                                fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(120))
                            }
                        }.using(
                            SizeTransform(
                                clip = false,
                                sizeAnimationSpec = { _, _ ->
                                    tween(300, easing = FastOutSlowInEasing)
                                },
                            ),
                        ),
                        contentAlignment = Alignment.TopCenter,
                        label = "share-content",
                    ) { key ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (val current = key.phase) {
                                SharePhase.Working ->
                                    BusyContent(stringResource(R.string.share_dialog_saving))

                                SharePhase.Saving ->
                                    BusyContent(stringResource(R.string.schedule_saving))

                                SharePhase.Empty -> EmptyContent(onClose)

                                is SharePhase.Added -> AddedContent(current.detail, onView, onClose)

                                SharePhase.Scheduling -> {
                                    when (val schedule = plan) {
                                        null -> BusyContent(stringResource(R.string.share_dialog_saving))

                                        SchedulePlan.Manual -> when (key.step) {
                                            ScheduleStep.Auto, ScheduleStep.Time -> TimeStep(
                                                hour = hour,
                                                minute = minute,
                                                cancelLabel = stringResource(R.string.action_close),
                                                onHour = onHour,
                                                onMinute = onMinute,
                                                onCancel = onCancel,
                                                onNext = onNext,
                                            )

                                            ScheduleStep.Date -> DateStep(
                                                hour = hour,
                                                minute = minute,
                                                date = date,
                                                auto = null,
                                                onDate = onDate,
                                                onBack = onBack,
                                                onConfirm = onConfirm,
                                            )
                                        }

                                        is SchedulePlan.Auto -> when (key.step) {
                                            ScheduleStep.Auto -> AutoStep(
                                                at = schedule.at,
                                                onCancel = onCancel,
                                                onEdit = onEdit,
                                                onConfirm = onConfirm,
                                            )

                                            ScheduleStep.Time -> TimeStep(
                                                hour = hour,
                                                minute = minute,
                                                cancelLabel = stringResource(R.string.schedule_back),
                                                onHour = onHour,
                                                onMinute = onMinute,
                                                onCancel = onReset,
                                                onNext = onNext,
                                            )

                                            ScheduleStep.Date -> DateStep(
                                                hour = hour,
                                                minute = minute,
                                                date = date,
                                                auto = schedule.at,
                                                onDate = onDate,
                                                onBack = onBack,
                                                onConfirm = onConfirm,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** أيقونة التطبيق أعلى النافذة: دائرة بتدرّج إنستغرام. */
@Composable
private fun MarkBadge() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(InstagramGradient)),
        contentAlignment = Alignment.Center,
    ) {
        ReelGlyph(color = Color.White, modifier = Modifier.size(21.dp))
    }
}

/** مؤشر الخطوات: نقطتان تتوسّع النشِطة منهما بحركة نابضة. */
@Composable
private fun StepDots(index: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { position ->
            val selected = position == index
            val width by animateDpAsState(
                targetValue = if (selected) 18.dp else 6.dp,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "step-dot",
            )
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) scheme.primary else scheme.outline),
            )
        }
    }
}

@Composable
private fun BusyContent(label: String) {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 2.dp,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun EmptyContent(onClose: () -> Unit) {
    Text(
        text = stringResource(R.string.share_dialog_failed_title),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(7.dp))
    Text(
        text = stringResource(R.string.share_dialog_failed_body),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    OutlinedButton(
        onClick = onClose,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Text(
            text = stringResource(R.string.action_close),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun AddedContent(detail: String, onView: () -> Unit, onClose: () -> Unit) {
    Text(
        text = stringResource(R.string.share_dialog_title),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
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
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Text(
                text = stringResource(R.string.action_view),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        OutlinedButton(
            onClick = onClose,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeStep(
    hour: Int,
    minute: Int,
    cancelLabel: String,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = false,
    )

    // المنتقي يحفظ وقته داخلياً؛ نرفع الاختيار للشاشة كل مرة يتغير فيها.
    LaunchedEffect(timeState.hour, timeState.minute) {
        onHour(timeState.hour)
        onMinute(timeState.minute)
    }

    StepTitle(
        title = stringResource(R.string.schedule_title),
        subtitle = stringResource(R.string.schedule_time_step),
    )

    Spacer(Modifier.height(8.dp))

    TimePicker(state = timeState)

    Spacer(Modifier.height(6.dp))
    ZoneLabel()

    Spacer(Modifier.height(14.dp))
    DialogButtons(
        cancelLabel = cancelLabel,
        confirmLabel = stringResource(R.string.schedule_next),
        onCancel = onCancel,
        onConfirm = onNext,
        confirmEnabled = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateStep(
    hour: Int,
    minute: Int,
    date: LocalDate?,
    auto: Long?,
    onDate: (LocalDate) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val today = ScheduleSlots.dateOf(System.currentTimeMillis())

    // التواريخ الماضية (أو اليوم بوقت مضى) غير قابلة للاختيار من التقويم نفسه.
    val selectable = remember(hour, minute) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = utcMillisToDate(utcTimeMillis)
                return ScheduleSlots.at(day, hour, minute) > System.currentTimeMillis()
            }

            override fun isSelectableYear(year: Int): Boolean =
                year >= ScheduleSlots.dateOf(System.currentTimeMillis()).year
        }
    }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date?.toUtcMillis(),
        initialDisplayedMonthMillis = (date ?: today).toUtcMillis(),
        selectableDates = selectable,
    )

    LaunchedEffect(pickerState.selectedDateMillis) {
        val millis = pickerState.selectedDateMillis ?: return@LaunchedEffect
        onDate(utcMillisToDate(millis))
    }

    StepTitle(
        title = stringResource(R.string.schedule_title),
        subtitle = stringResource(R.string.schedule_date_step),
    )
    Text(
        text = ScheduleSlots.formatTime(hour, minute) + " · " +
            stringResource(R.string.schedule_zone),
        color = scheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(6.dp))

    DatePicker(
        state = pickerState,
        showModeToggle = false,
        title = null,
        headline = null,
        colors = DatePickerDefaults.colors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    val selectedAt = date?.let { ScheduleSlots.at(it, hour, minute) }
    val valid = selectedAt != null && selectedAt > System.currentTimeMillis()

    AnimatedContent(
        targetState = valid,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        },
        label = "publish-summary",
    ) { enabled ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (enabled && selectedAt != null) {
                    stringResource(
                        R.string.schedule_publish_at,
                        ScheduleSlots.formatFull(selectedAt),
                    )
                } else {
                    stringResource(R.string.schedule_past)
                },
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (auto != null) {
                Text(
                    text = stringResource(
                        R.string.schedule_auto_keep,
                        ScheduleSlots.formatFull(auto),
                    ),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    DialogButtons(
        cancelLabel = stringResource(R.string.schedule_back),
        confirmLabel = stringResource(R.string.schedule_confirm),
        onCancel = onBack,
        onConfirm = onConfirm,
        confirmEnabled = valid,
    )
}

@Composable
private fun AutoStep(
    at: Long,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    StepTitle(
        title = stringResource(R.string.schedule_auto_title),
        subtitle = stringResource(R.string.schedule_auto_body),
    )

    Spacer(Modifier.height(14.dp))

    // الحاوية اللونية تعرض الموعد التلقائي، وهو الافتراضي إن لم يغيّره المستخدم.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = ScheduleSlots.formatFull(at),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.schedule_zone),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.schedule_auto_hint),
        color = scheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(12.dp))
    FilledTonalButton(
        onClick = onEdit,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(R.string.schedule_change),
            style = MaterialTheme.typography.labelLarge,
        )
    }

    Spacer(Modifier.height(10.dp))
    DialogButtons(
        cancelLabel = stringResource(R.string.action_close),
        confirmLabel = stringResource(R.string.schedule_confirm),
        onCancel = onCancel,
        onConfirm = onConfirm,
        confirmEnabled = true,
    )
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = subtitle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ZoneLabel() {
    Text(
        text = stringResource(R.string.schedule_zone),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun DialogButtons(
    cancelLabel: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(
            onClick = onCancel,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Text(text = cancelLabel, style = MaterialTheme.typography.labelLarge)
        }

        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** تاريخ محلي → منتصف الليل بتوقيت UTC كما يتوقعه DatePicker. */
private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** ميللي UTC القادم من DatePicker → تاريخ محلي. */
private fun utcMillisToDate(utcTimeMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
