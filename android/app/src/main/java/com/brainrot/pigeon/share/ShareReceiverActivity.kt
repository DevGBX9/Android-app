package com.brainrot.pigeon.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
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
import com.brainrot.pigeon.ui.theme.Accent
import com.brainrot.pigeon.ui.theme.Background
import com.brainrot.pigeon.ui.theme.CardSurface
import com.brainrot.pigeon.ui.theme.Outline
import com.brainrot.pigeon.ui.theme.PigeonTheme
import com.brainrot.pigeon.ui.theme.TextMuted
import com.brainrot.pigeon.ui.theme.TextPrimary
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

private val InstagramGradient = listOf(Color(0xFFF58529), Color(0xFFE1306C), Color(0xFF8134AF))

private sealed interface SharePhase {
    data object Working : SharePhase
    data object Empty : SharePhase
    data object Scheduling : SharePhase
    data object Saving : SharePhase
    data class Added(val detail: String) : SharePhase
}

private enum class ScheduleStep { Time, Date }

/**
 * شاشة شفافة فوق التطبيق الذي شارك الريل (إنستغرام مثلاً): تحضّر الريل أولاً،
 * تعرض خطوة اختيار وقت النشر ثم التاريخ (أول ريل فقط)، وبعد التأكيد تحفظه
 * وترفع موعده إلى Firebase لينشره Cloudflare في وقته.
 */
class ShareReceiverActivity : ComponentActivity() {

    private var phase by mutableStateOf<SharePhase>(SharePhase.Working)
    private var plan by mutableStateOf<SchedulePlan?>(null)
    private var step by mutableStateOf(ScheduleStep.Time)
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
                    step = ScheduleStep.Date
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
                    onConfirm = {
                        when (val current = plan) {
                            is SchedulePlan.Auto -> save(current.at, container.saved)
                            else -> {
                                val now = System.currentTimeMillis()
                                val chosen = date?.let {
                                    ScheduleSlots.at(it, hour, minute)
                                }
                                if (chosen != null && chosen > now) {
                                    save(chosen, container.saved)
                                } else {
                                    // مرّ الوقت المختار أثناء الاختيار: نعيد لموعد صالح.
                                    date = SchedulePlanner.defaultManualDate(hour, minute, now)
                                }
                            }
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
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onView: () -> Unit,
    onClose: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val busy = phase == SharePhase.Working || phase == SharePhase.Saving

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !busy,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(170)) + scaleIn(tween(230), initialScale = 0.9f),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = CardSurface,
                shadowElevation = 24.dp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
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
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(InstagramGradient)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ReelGlyph(color = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Spacer(Modifier.height(14.dp))

                    when (phase) {
                        SharePhase.Working -> BusyContent(stringResource(R.string.share_dialog_saving))
                        SharePhase.Saving -> BusyContent(stringResource(R.string.schedule_saving))
                        SharePhase.Empty -> EmptyContent(onClose)
                        is SharePhase.Added -> AddedContent(phase.detail, onView, onClose)
                        SharePhase.Scheduling -> {
                            when (val current = plan) {
                                null -> BusyContent(stringResource(R.string.share_dialog_saving))
                                SchedulePlan.Manual -> when (step) {
                                    ScheduleStep.Time -> TimeStep(
                                        hour = hour,
                                        minute = minute,
                                        onHour = onHour,
                                        onMinute = onMinute,
                                        onCancel = onCancel,
                                        onNext = onNext,
                                    )

                                    ScheduleStep.Date -> DateStep(
                                        hour = hour,
                                        minute = minute,
                                        date = date,
                                        onDate = onDate,
                                        onBack = onBack,
                                        onConfirm = onConfirm,
                                    )
                                }

                                is SchedulePlan.Auto -> AutoStep(
                                    at = current.at,
                                    onCancel = onCancel,
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

@Composable
private fun BusyContent(label: String) {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        color = TextMuted,
        strokeWidth = 2.dp,
    )
    Spacer(Modifier.height(10.dp))
    Text(text = label, color = TextMuted, fontSize = 13.sp)
}

@Composable
private fun EmptyContent(onClose: () -> Unit) {
    Text(
        text = stringResource(R.string.share_dialog_failed_title),
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(7.dp))
    Text(
        text = stringResource(R.string.share_dialog_failed_body),
        color = TextMuted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    OutlinedButton(
        onClick = onClose,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
    ) {
        Text(
            text = stringResource(R.string.action_close),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AddedContent(detail: String, onView: () -> Unit, onClose: () -> Unit) {
    Text(
        text = stringResource(R.string.share_dialog_title),
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = detail,
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
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
            colors = ButtonDefaults.buttonColors(
                containerColor = TextPrimary,
                contentColor = Background,
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text(
                text = stringResource(R.string.action_view),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedButton(
            onClick = onClose,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text(
                text = stringResource(R.string.action_close),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TimeStep(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = stringResource(R.string.schedule_title),
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.schedule_time_step),
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        // الوقت بنظام 12 ساعة: 7:00 PM مساءً أو 7:00 AM صباحاً.
        text = ScheduleSlots.formatTime(hour, minute),
        color = TextPrimary,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        text = stringResource(R.string.schedule_zone),
        color = TextMuted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.schedule_hour),
                color = TextMuted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                state = rememberLazyListState(
                    initialFirstVisibleItemIndex = ScheduleSlots.hour12(hour) - 1,
                ),
            ) {
                items(12) { index ->
                    val value = index + 1
                    ChoiceChip(
                        text = String.format(Locale.US, "%02d", value),
                        selected = value == ScheduleSlots.hour12(hour),
                        onClick = {
                            onHour(ScheduleSlots.hour24(value, ScheduleSlots.isPm(hour)))
                        },
                    )
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.schedule_minute),
                color = TextMuted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                state = rememberLazyListState(
                    initialFirstVisibleItemIndex = minute,
                ),
            ) {
                items(60) { value ->
                    ChoiceChip(
                        text = String.format(Locale.US, "%02d", value),
                        selected = value == minute,
                        onClick = { onMinute(value) },
                    )
                }
            }
        }

        // AM / PM: يبدّل بين صباح ومساء مع بقاء الساعة الظاهرة كما هي.
        Column(Modifier.weight(0.85f)) {
            Text(
                text = stringResource(R.string.schedule_meridiem),
                color = TextMuted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(4.dp))
            val pm = ScheduleSlots.isPm(hour)
            val hour12 = ScheduleSlots.hour12(hour)
            ChoiceChip(
                text = stringResource(R.string.schedule_am),
                selected = !pm,
                onClick = { onHour(ScheduleSlots.hour24(hour12, false)) },
            )
            ChoiceChip(
                text = stringResource(R.string.schedule_pm),
                selected = pm,
                onClick = { onHour(ScheduleSlots.hour24(hour12, true)) },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    DialogButtons(
        cancelLabel = stringResource(R.string.action_close),
        confirmLabel = stringResource(R.string.schedule_next),
        onCancel = onCancel,
        onConfirm = onNext,
        confirmEnabled = true,
    )
}

@Composable
private fun DateStep(
    hour: Int,
    minute: Int,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val today = ScheduleSlots.dateOf(now)
    val days = remember(today) { (0 until 90).map { today.plusDays(it.toLong()) } }
    val selectedAt = date?.let { ScheduleSlots.at(it, hour, minute) }
    val valid = selectedAt != null && selectedAt > now

    Text(
        text = stringResource(R.string.schedule_title),
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.schedule_date_step),
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = ScheduleSlots.formatTime(hour, minute) +
            " · " + stringResource(R.string.schedule_zone),
        color = TextMuted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(10.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(186.dp),
    ) {
        items(days.size) { index ->
            val day = days[index]
            DateRow(
                date = day,
                selected = day == date,
                enabled = ScheduleSlots.at(day, hour, minute) > now,
                isToday = day == today,
                onClick = { onDate(day) },
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = if (valid && selectedAt != null) {
            stringResource(R.string.schedule_publish_at, ScheduleSlots.formatFull(selectedAt))
        } else {
            stringResource(R.string.schedule_past)
        },
        color = if (valid) TextPrimary else TextMuted,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
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
private fun AutoStep(at: Long, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Text(
        text = stringResource(R.string.schedule_auto_title),
        color = TextPrimary,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(7.dp))
    Text(
        text = stringResource(R.string.schedule_auto_body),
        color = TextMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = ScheduleSlots.formatFull(at),
        color = TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.schedule_zone),
        color = TextMuted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(16.dp))
    DialogButtons(
        cancelLabel = stringResource(R.string.action_close),
        confirmLabel = stringResource(R.string.schedule_confirm),
        onCancel = onCancel,
        onConfirm = onConfirm,
        confirmEnabled = true,
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
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text(text = cancelLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = TextPrimary,
                contentColor = Background,
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text(text = confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Background else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DateRow(
    date: LocalDate,
    selected: Boolean,
    enabled: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val label = if (isToday) {
        stringResource(R.string.schedule_today, ScheduleSlots.formatDate(date))
    } else {
        ScheduleSlots.formatDate(date)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = if (selected) {
                Background
            } else if (enabled) {
                TextPrimary
            } else {
                TextMuted.copy(alpha = 0.4f)
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
