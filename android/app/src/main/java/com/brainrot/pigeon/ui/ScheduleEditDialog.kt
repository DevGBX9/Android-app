package com.brainrot.pigeon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.brainrot.pigeon.R
import com.brainrot.pigeon.data.ScheduleSlots
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * نافذة تغيير موعد ريل موجود (من الضغطة المطوّلة في الشبكة) بنفس مظهر
 * Material You: تقويم DatePicker عند الحاجة ومنتقي وقت دائري TimePicker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditDialog(
    initialAt: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var date by remember { mutableStateOf(ScheduleSlots.dateOf(initialAt)) }
    val start = ScheduleSlots.timeOf(initialAt)
    val timeState = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = false,
    )
    var pickingDate by remember { mutableStateOf(false) }

    val chosen = ScheduleSlots.at(date, timeState.hour, timeState.minute)
    val valid = chosen > System.currentTimeMillis()
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.schedule_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(scheme.secondaryContainer)
                        .clickable { pickingDate = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarGlyph(
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = ScheduleSlots.formatDate(date),
                        color = scheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
                Text(
                    text = stringResource(R.string.schedule_zone),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (valid) {
                        stringResource(
                            R.string.schedule_publish_at,
                            ScheduleSlots.formatFull(chosen),
                        )
                    } else {
                        stringResource(R.string.schedule_past)
                    },
                    color = if (valid) scheme.onSurface else scheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Button(
                        onClick = { onConfirm(chosen) },
                        enabled = valid,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.schedule_confirm),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }

    if (pickingDate) {
        // لا يمكن اختيار يوم مضى؛ اليوم نفسه مسموح لأن الوقت يُفحص عند التأكيد.
        val selectable = remember {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcMidnightToDate(utcTimeMillis) >=
                        ScheduleSlots.dateOf(System.currentTimeMillis())

                override fun isSelectableYear(year: Int): Boolean =
                    year >= ScheduleSlots.dateOf(System.currentTimeMillis()).year
            }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMidnight(),
            selectableDates = selectable,
        )

        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { date = utcMidnightToDate(it) }
                        pickingDate = false
                    },
                ) {
                    Text(stringResource(R.string.schedule_next))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) {
                    Text(stringResource(R.string.schedule_back))
                }
            },
        ) {
            DatePicker(
                state = pickerState,
                showModeToggle = false,
                title = null,
                headline = null,
                colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** تاريخ محلي → منتصف الليل بتوقيت UTC كما يتوقعه DatePicker. */
private fun LocalDate.toUtcMidnight(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** ميللي UTC القادم من DatePicker → تاريخ محلي. */
private fun utcMidnightToDate(utcTimeMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
