package com.fragmind.camnode.feature.applock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fragmind.camnode.core.designsystem.CamNodeIcons
import com.fragmind.camnode.core.model.AppPin
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.util.Locale


/**
 * A month calendar whose events go to the system calendar app. Adding an event on the
 * date DD/MM equal to the PIN opens CamNode instead.
 */
@Composable
internal fun CalendarDisguise(onPinCandidate: (String, onDenied: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var shownMonth by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedDay by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(shownMonth)
    val selected = LocalDate.parse(selectedDay)
    val locale = LocalConfiguration.current.locales[0]

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onPinCandidate(AppPin.fromDate(selected.dayOfMonth, selected.monthValue)) { context.insertEvent(selected) } },
                icon = { Icon(CamNodeIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.applock_calendar_new_event)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shownMonth = month.minusMonths(1).toString() }) { Icon(CamNodeIcons.ChevronLeft, contentDescription = stringResource(R.string.applock_calendar_previous)) }
                Text(
                    month.format(bestPattern(locale, "LLLLyyyy")).replaceFirstChar { it.titlecase(locale) },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { shownMonth = month.plusMonths(1).toString() }) { Icon(CamNodeIcons.ChevronRight, contentDescription = stringResource(R.string.applock_calendar_next)) }
            }
            MonthGrid(month, today, selected, locale, onSelect = { selectedDay = it.toString() }, modifier = Modifier.widthIn(max = 480.dp))
            Text(
                stringResource(R.string.applock_calendar_no_events, selected.format(bestPattern(locale, "EEEEdMMMM")).replaceFirstChar { it.titlecase(locale) }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, selected: LocalDate, locale: Locale, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val weekdays = List(7) { firstDay.plus(it.toLong()) }
    val leadingBlanks = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { day ->
                Text(day.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date -> DayCell(date, isToday = date == today, isSelected = date == selected, onSelect = onSelect, modifier = Modifier.weight(1f)) }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate?, isToday: Boolean, isSelected: Boolean, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
        if (date == null) return@Box
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier.fillMaxSize()
                .then(if (isSelected) Modifier.background(colors.primary, CircleShape) else Modifier)
                .then(if (isToday && !isSelected) Modifier.border(1.dp, colors.primary, CircleShape) else Modifier)
                .clip(CircleShape)
                .clickable { onSelect(date) },
            contentAlignment = Alignment.Center,
        ) {
            Text(date.dayOfMonth.toString(), color = if (isSelected) colors.onPrimary else colors.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun Context.insertEvent(date: LocalDate) {
    val begin = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000L)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.applock_calendar_no_app, Toast.LENGTH_SHORT).show()
    }
}
