package dev.jdcm.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.jdcm.R
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.core.model.MADRID
import dev.jdcm.core.model.Match
import dev.jdcm.core.model.Team
import dev.jdcm.core.standings.Outcome
import dev.jdcm.core.standings.PointsRule
import dev.jdcm.core.standings.Standings
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.components.Crest
import dev.jdcm.ui.components.Hairline
import dev.jdcm.ui.components.MatchCard
import dev.jdcm.ui.components.StandingsTable
import dev.jdcm.ui.components.sportName
import dev.jdcm.ui.format.Format
import dev.jdcm.ui.theme.JdcmColors
import dev.jdcm.ui.theme.Tabular
import dev.jdcm.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * «Compartir como imagen»: a result, a round or a table drawn in the app's own style, for the
 * team's WhatsApp group (owner, 2026-11-19). The sheet shows the image as it will be sent — what
 * is drawn there is what is captured — with the plain text as the other way when there is one.
 * The PNG goes through the app's cache and its FileProvider (`<package>.share`), never storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImageSheet(
    /** No names: ids only («result-10467»), for the file name and the log. */
    id: String,
    onDismiss: () -> Unit,
    onShareText: (() -> Unit)? = null,
    card: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.share_image_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            val previewDesc = stringResource(R.string.share_preview_desc)
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, JdcmColors.extra.line, RoundedCornerShape(12.dp))
                    // One picture to TalkBack, not a dozen rows that do nothing.
                    .clearAndSetSemantics { contentDescription = previewDesc },
            ) {
                // Recorded as it is drawn: the image is exactly the preview.
                Box(Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) { card() }
                // Over it, taps go nowhere (no ripple can end up in the image); drags still scroll the sheet.
                Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
            }
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val file = runCatchingCancellable {
                            val bmp = layer.toImageBitmap().asAndroidBitmap()
                            withContext(Dispatchers.IO) { writePng(ctx, bmp, id) }
                        }.onFailure { DiagLog.w("UI", "share image $id failed", it) }.getOrNull()
                        busy = false
                        if (file == null) {
                            Toast.makeText(ctx, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            DiagLog.i("UI", "share image $id ${file.length() / 1024} KB")
                            sendPng(ctx, file)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp),
            ) {
                Icon(Icons.Outlined.Image, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_image))
            }
            if (onShareText != null) {
                OutlinedButton(onClick = onShareText, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.share_text))
                }
            }
        }
    }
}

/** A door to a share sheet under a card: a full-width row, never a tiny icon (Home's rule). */
@Composable
fun ShareRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Image, null, tint = JdcmColors.extra.link, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = JdcmColors.extra.link)
    }
}

/**
 * Images in `cache/share`, kept an hour (an app may read one a little later: a draft, a queued upload);
 * older ones go when a new one is made.
 */
private fun writePng(ctx: Context, bmp: Bitmap, id: String): File {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val hourAgo = System.currentTimeMillis() - 3_600_000L
    dir.listFiles()?.filter { it.lastModified() < hourAgo }?.forEach { it.delete() }
    val file = File(dir, "jdcm-$id.png")
    // A hardware bitmap (the layer's snapshot) cannot be compressed as it is.
    val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
    file.outputStream().use { check(soft.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG not written" } }
    return file
}

private fun sendPng(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }.onFailure { DiagLog.w("UI", "no app to share an image", it) }
}

// --- the cards ----------------------------------------------------------------------------------

/** «Fútbol sala · Alevín mixto · Grupo 3»: what the group is, without a child's name. */
@Composable
fun groupLine(snap: GroupSnapshot?): String {
    if (snap == null) return ""
    val competition = snap.group.competition.lowercase().replaceFirstChar { it.titlecase() }
    return listOf(sportName(snap.sport, snap.group.sport), competition, snap.group.name).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

/** The frame every image shares: the app's mark, what it is, the content, and where the data comes from. */
@Composable
private fun ShareFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    val extra = JdcmColors.extra
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 14.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_stat_jdcm), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.share_card_source), style = MaterialTheme.typography.labelSmall, color = extra.muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/** One match: a result (score, «Finalizado», partials) or one to come (day, time, field). */
@Composable
fun ResultShareCard(m: Match, snap: GroupSnapshot?, highlightTeamId: Int, accent: Color?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val extra = JdcmColors.extra
    val sub = listOfNotNull(stringResource(R.string.round_n, m.roundNumber), m.day?.let(fmt::day)?.takeIf { m.hasResult }).joinToString(" · ")
    ShareFrame(groupLine(snap), sub) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            ShareTeam(m.home, m, highlightTeamId, accent, Modifier.weight(1f))
            Column(Modifier.width(118.dp).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (m.hasResult) {
                    Text("${m.homeScore} - ${m.awayScore}", style = MaterialTheme.typography.displaySmall.merge(Tabular), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.status_final).uppercase(), style = MaterialTheme.typography.labelMedium, color = extra.muted)
                } else {
                    Text(fmt.time(m), style = MaterialTheme.typography.displaySmall.merge(Tabular), fontWeight = FontWeight.Bold)
                    m.day?.let { Text(fmt.day(it), style = MaterialTheme.typography.labelLarge, color = extra.muted) }
                    fmt.statusLabel(m)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (m.isCancelled) extra.danger else extra.modified)
                    }
                }
            }
            ShareTeam(m.away, m, highlightTeamId, accent, Modifier.weight(1f))
        }
        if (m.hasResult && m.partials.isNotEmpty()) {
            Text(
                m.partials.joinToString("   "), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = extra.muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!m.hasResult && m.venue != null) {
            Text(
                fmt.venueLine(m.venue), style = MaterialTheme.typography.bodySmall, color = extra.muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ShareTeam(team: Team?, m: Match, highlightTeamId: Int, accent: Color?, modifier: Modifier) {
    val won = m.hasResult && team != null && Standings.outcome(m, team.id) == Outcome.WIN
    val mine = team != null && team.id == highlightTeamId
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Crest(team, 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            team?.name ?: stringResource(R.string.rests), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            fontWeight = if (won || mine) FontWeight.Bold else FontWeight.Normal, maxLines = 3,
        )
        if (mine) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(accent ?: MaterialTheme.colorScheme.primary))
        }
    }
}

/** A round's matches, the followed team's in its colour. */
@Composable
fun RoundShareCard(snap: GroupSnapshot, roundId: Int, highlightTeamId: Int, accent: Color?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val round = snap.rounds.firstOrNull { it.id == roundId }
    val matches = remember(snap, roundId) { snap.roundMatches(roundId) }
    val sub = listOfNotNull(round?.let { stringResource(R.string.round_n, it.number) }, round?.date?.let(fmt::day)).joinToString(" · ")
    ShareFrame(groupLine(snap), sub) {
        matches.forEachIndexed { i, m ->
            if (i > 0) Hairline()
            MatchCard(m, onClick = {}, highlightTeamId = highlightTeamId, accent = accent, compact = true, showDate = true)
        }
    }
}

/** The computed table, with its rule and «not official» note (StandingsTable's own footnote). */
@Composable
fun TableShareCard(snap: GroupSnapshot, highlightTeamId: Int, accent: Color?) {
    val ctx = LocalContext.current
    val fmt = remember(ctx) { Format(ctx) }
    val rule = PointsRule.forSport(snap.sport)
    val rows = remember(snap) { Standings.compute(snap.matches, rule) }
    ShareFrame(groupLine(snap), stringResource(R.string.share_table_sub, fmt.dayMonth(LocalDate.now(MADRID)))) {
        StandingsTable(rows, rule, highlightTeamId, accent, onTeam = {})
    }
}
