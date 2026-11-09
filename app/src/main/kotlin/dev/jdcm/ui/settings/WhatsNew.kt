package dev.jdcm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdcm.AppContainer
import dev.jdcm.BuildConfig
import dev.jdcm.R
import dev.jdcm.diag.DiagLog
import dev.jdcm.ui.components.FlatCard
import dev.jdcm.ui.explore.BackBar
import dev.jdcm.ui.theme.JdcmColors

/**
 * «Novedades», once per update: only this version's note (three short bullets at most) and a
 * link to the rest. A fresh install has nothing new to tell, so it says nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(c: AppContainer, onAll: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<ReleaseNote?>(null) }
    LaunchedEffect(Unit) {
        val seen = c.settings.seenVersionCode
        if (seen >= BuildConfig.VERSION_CODE) return@LaunchedEffect
        val current = Changelog.load(ctx).firstOrNull { it.version == BuildConfig.VERSION_NAME }
        if (seen == 0 || current == null) {
            c.settings.seenVersionCode = BuildConfig.VERSION_CODE
            return@LaunchedEffect
        }
        DiagLog.i("UI", "whats new shown for ${BuildConfig.VERSION_NAME} (seen $seen)")
        note = current
    }
    val n = note ?: return
    val lang = ctx.resources.configuration.locales[0]?.language ?: "es"
    fun close() {
        c.settings.seenVersionCode = BuildConfig.VERSION_CODE
        note = null
    }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.titleLarge)
            Text(n.version.substringBefore('-'), style = MaterialTheme.typography.bodySmall, color = JdcmColors.extra.muted)
            Spacer(Modifier.height(14.dp))
            n.bullets(lang).take(Changelog.MAX_BULLETS).forEach { b ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(b, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { close(); onAll() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.whats_new_all)) }
                Button(onClick = ::close, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.got_it)) }
            }
        }
    }
}

@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notes = remember { Changelog.load(ctx) }
    val lang = ctx.resources.configuration.locales[0]?.language ?: "es"
    Scaffold(topBar = { BackBar(stringResource(R.string.whats_new), onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(notes, key = { it.version }) { n ->
                FlatCard(Modifier.padding(vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(n.version.substringBefore('-'), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        n.bullets(lang).forEach { b -> Text("• $b", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
            }
            if (notes.isEmpty()) item { Text(stringResource(R.string.nothing_here), Modifier.padding(20.dp)) }
        }
    }
}
