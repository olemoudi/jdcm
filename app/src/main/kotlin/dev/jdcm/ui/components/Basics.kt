package dev.jdcm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsVolleyball
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.jdcm.R
import dev.jdcm.core.model.SportKind
import dev.jdcm.core.model.Team
import dev.jdcm.data.EcmApi
import dev.jdcm.data.SyncStatus
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.theme.JdcmColors

fun sportIcon(kind: SportKind): ImageVector = when (kind) {
    SportKind.BASKETBALL -> Icons.Filled.SportsBasketball
    SportKind.VOLLEYBALL -> Icons.Filled.SportsVolleyball
    SportKind.FUTSAL -> Icons.Filled.SportsSoccer
    SportKind.OTHER -> Icons.Filled.EmojiEvents
}

@Composable
fun sportName(kind: SportKind, fallback: String = ""): String = when (kind) {
    SportKind.BASKETBALL -> stringResource(R.string.sport_basketball)
    SportKind.VOLLEYBALL -> stringResource(R.string.sport_volleyball)
    SportKind.FUTSAL -> stringResource(R.string.sport_futsal)
    SportKind.OTHER -> fallback
}

/**
 * A school's emblem in a round frame (the site's own look). Initials on a colour drawn from the
 * name sit underneath: they show while the image loads and stay when a school has none.
 */
@Composable
fun Crest(team: Team?, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val name = team?.name.orEmpty()
    val initials = remember(name) { initials(name) }
    val hue = remember(name) { (name.hashCode().toUInt() % 360u).toFloat() }
    val extra = JdcmColors.extra
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.hsl(hue, 0.35f, if (extra.dark) 0.28f else 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        val url = team?.crestPath?.let(EcmApi::crestUrl)
        if (url != null) {
            Box(Modifier.size(size).background(Color.White.copy(alpha = if (extra.dark) 0.92f else 1f), CircleShape), contentAlignment = Alignment.Center) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size * 0.78f))
            }
        }
    }
}

private val STOP = setOf("DE", "LA", "EL", "LOS", "LAS", "Y", "DEL", "SAN", "SANTA", "COLEGIO", "FEC", "NUESTRA", "SEÑORA")

fun initials(name: String): String {
    val words = name.uppercase().replace(Regex("\"[^\"]*\""), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    val meaningful = words.filter { it !in STOP }.ifEmpty { words }
    return meaningful.take(2).joinToString("") { it.take(1) }.ifEmpty { "?" }
}

/** A section title in the Marca style: an icon, a bold name and, on the right, a small detail. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (accent != null) {
            Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
        }
        if (icon != null) {
            Icon(icon, null, tint = accent ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The quiet line that says how fresh the data is, or why it is not. */
@Composable
fun FreshnessLine(status: SyncStatus?, newestFetchMs: Long, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val extra = JdcmColors.extra
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        when {
            status?.running == true -> {
                val label = if (status.total > 0) stringResource(R.string.sync_progress, status.done, status.total)
                else stringResource(R.string.sync_starting)
                Text(label, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                Spacer(Modifier.height(4.dp))
                if (status.total > 0) {
                    LinearProgressIndicator(progress = { status.done.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            status?.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudOff, null, tint = extra.modified, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val why = if (status.offline) stringResource(R.string.sync_offline) else stringResource(R.string.sync_failed)
                val shown = if (newestFetchMs > 0) stringResource(R.string.sync_showing_from, fmt.age(newestFetchMs)) else ""
                Text("$why $shown".trim(), style = MaterialTheme.typography.bodySmall, color = extra.modified)
            }
            newestFetchMs > 0 -> Text(
                stringResource(R.string.updated_ago, fmt.age(newestFetchMs)),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

/** A full-width row that leads somewhere: the app's doors are rows, never tiny icons (rwilco). */
@Composable
fun DoorRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = JdcmColors.extra.muted)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = JdcmColors.extra.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (primary != null) Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(primary.first) }
        if (secondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(secondary.first) }
        }
    }
}

/** A bordered card like Marca's: flat, a hairline around it, square-ish corners. */
@Composable
fun FlatCard(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    val extra = JdcmColors.extra
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(if (borderColor != null) 1.5.dp else 1.dp, borderColor ?: extra.line, RoundedCornerShape(10.dp)),
    ) { Column { content() } }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = JdcmColors.extra.line)

@Composable
fun InfoChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
    )
}

@Composable
fun RowSpaced(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
