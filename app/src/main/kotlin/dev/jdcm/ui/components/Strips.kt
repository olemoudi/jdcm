package dev.jdcm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdcm.R
import dev.jdcm.core.model.SportKind
import dev.jdcm.data.Kid
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular
import dev.jdcm.ui.theme.kidColor
import java.time.LocalDate

/**
 * Marca's day strip, made for a weekend league: one cell per matchday (its Saturday), the month
 * above, "Ver hoy" and a calendar to jump. Coloured dots under a day say which children play.
 */
@Composable
fun MatchdayStrip(
    days: List<LocalDate>,
    selected: LocalDate?,
    current: LocalDate?,
    today: LocalDate,
    dotsFor: (LocalDate) -> List<Color>,
    onSelect: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onCalendar: () -> Unit,
) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val extra = JdcmColors.extra
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (selected ?: today).let(fmt::monthYear),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).height(28.dp).background(extra.line))
            TextButton(onClick = onToday) { Text(stringResource(R.string.see_today), color = extra.link) }
            IconButton(onClick = onCalendar) { Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.pick_date)) }
        }
        val state = rememberLazyListState()
        val index = days.indexOf(selected)
        LaunchedEffect(index, days.size) {
            if (index >= 0) state.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            itemsIndexed(days, key = { _, d -> d.toEpochDay() }) { _, d ->
                val isSel = d == selected
                val isCurrent = d == current
                val thisWeekend = !today.isBefore(d.minusDays(1)) && !today.isAfter(d.plusDays(1))
                val top = when {
                    d == today -> stringResource(R.string.today_short)
                    thisWeekend -> stringResource(R.string.this_weekend_short)
                    else -> fmt.monthShort(d)
                }
                Column(
                    Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(d) }
                        .semantics { this.selected = isSel; contentDescription = fmt.day(d) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        top,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isSel -> MaterialTheme.colorScheme.onPrimary
                            isCurrent || thisWeekend -> MaterialTheme.colorScheme.primary
                            else -> extra.muted
                        },
                        fontWeight = if (isCurrent || thisWeekend) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        d.dayOfMonth.toString(),
                        style = MaterialTheme.typography.headlineSmall.merge(Tabular),
                        color = when {
                            isSel -> MaterialTheme.colorScheme.onPrimary
                            isCurrent || thisWeekend -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (isSel || isCurrent) FontWeight.Bold else FontWeight.Normal,
                    )
                    Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        dotsFor(d).take(4).forEach { c ->
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(c)
                                    .border(if (isSel) 1.dp else 0.dp, if (isSel) Color.White else Color.Transparent, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The children as big pills: one tap filters the whole screen to that child. */
@Composable
fun KidPills(
    kids: List<Kid>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (kids.size > 1) {
            item(key = "all") {
                Pill(stringResource(R.string.all_kids), null, null, selectedId == null) { onSelect(null) }
            }
        }
        itemsIndexed(kids, key = { _, k -> k.id }) { _, k ->
            Pill(k.name, kidColor(k.color), SportKind.of(k.sportId, k.sportName), selectedId == k.id || kids.size == 1) { onSelect(k.id) }
        }
        if (onAdd != null) {
            item(key = "add") {
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).border(1.dp, JdcmColors.extra.line, RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, stringResource(R.string.add_kid), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color?, sport: SportKind?, selected: Boolean, onClick: () -> Unit) {
    val extra = JdcmColors.extra
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        )
        if (sport != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                sportIcon(sport), null, modifier = Modifier.size(16.dp),
                tint = if (selected) MaterialTheme.colorScheme.surface else extra.muted,
            )
        }
    }
}
