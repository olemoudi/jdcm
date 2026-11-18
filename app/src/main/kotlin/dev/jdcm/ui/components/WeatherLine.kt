package dev.jdcm.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Roofing
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdcm.R
import dev.jdcm.core.weather.MatchWeather
import dev.jdcm.ui.theme.JdcmColors

/** "AEMET's stretch": «de 12 a 24 h», or «todo el día». */
@Composable
fun stretchText(w: MatchWeather.Chance): String =
    if (w.fromHour == 0 && w.toHour == 24) stringResource(R.string.weather_whole_day)
    else stringResource(R.string.weather_stretch, w.fromHour, w.toHour)

/** How a chance is coloured: over the alert line red, a real chance amber, the rest quiet. */
@Composable
fun weatherTint(w: MatchWeather): Color = when {
    w is MatchWeather.Chance && w.alerting -> JdcmColors.extra.danger
    w is MatchWeather.Chance && w.rain >= 30 -> JdcmColors.extra.modified
    else -> JdcmColors.extra.muted
}

/** The match card's one line: «Lluvia 85 % de 12 a 24 h», or the roof. One figure: the chance it goes ahead is just its complement. */
@Composable
fun WeatherLine(w: MatchWeather, modifier: Modifier = Modifier) {
    val tint = weatherTint(w)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (w is MatchWeather.Covered) Icons.Outlined.Roofing else Icons.Outlined.Umbrella, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        val text = when (w) {
            MatchWeather.Covered -> stringResource(R.string.weather_covered)
            is MatchWeather.Chance -> stringResource(R.string.weather_line, w.rain, stretchText(w))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
