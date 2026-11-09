package dev.jdcm.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jdcm.R
import dev.jdcm.diag.DiagLog
import dev.jdcm.diag.copyDiagnostics
import dev.jdcm.diag.shareDiagnostics
import dev.jdcm.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Diagnostics. [buildReport] = `{ collectDiagnostics(app, works, ::appSections).render() }`.
 * Copy is the primary action; the preview shows the header and the last 150 log lines (one 100 KB Text is slow
 * to lay out); the full report goes to the clipboard. Rebuilds as lines land, keeping the old text meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(buildReport: suspend () -> String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var report by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val copiedFmt = stringResource(R.string.diag_copied)
    val copyFailed = stringResource(R.string.diag_copy_failed)

    LaunchedEffect(generation) {
        // A StateFlow is already conflated: lines that land during the delay collapse into one rebuild.
        DiagLog.version.collect {
            report = runCatchingCancellable { buildReport() }.getOrElse { e ->
                DiagLog.e("Diag", "report failed", e)
                // The screen must still give the user something to paste.
                "report failed (${e.javaClass.simpleName}: ${e.message})\n\n" +
                    withContext(Dispatchers.IO) { DiagLog.recent(60_000) }
            }
            delay(1_000) // at most one rebuild per second
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.diag_back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.diag_clear))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodyMedium)
            val text = report
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = text != null, onClick = {
                    val t = text ?: return@Button
                    val ok = ctx.copyDiagnostics(t)
                    scope.launch { snackbar.showSnackbar(if (ok) copiedFmt.format(t.length / 1024) else copyFailed) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.diag_copy))
                }
                OutlinedButton(enabled = text != null, onClick = { text?.let(ctx::shareDiagnostics) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.diag_share))
                }
            }
            Text(
                stringResource(R.string.diag_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth().weight(1f)) {
                if (text == null) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                } else {
                    val shown = remember(text) { preview(text) }
                    SelectionContainer {
                        Text(
                            shown,
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diag_clear_title)) },
            text = { Text(stringResource(R.string.diag_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { DiagLog.clear() }
                        generation++
                    }
                }) { Text(stringResource(R.string.diag_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

/**
 * One-tap copy from where trouble is noticed (main screen top bar, error states, health cards): three screens
 * deep, "by the time they have found it, the log has moved on" (rwilco 0.27.0).
 */
@Composable
fun CopyDiagnosticsAction(buildReport: suspend () -> String, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedFmt = stringResource(R.string.diag_copied)
    val copyFailed = stringResource(R.string.diag_copy_failed)
    IconButton(onClick = {
        scope.launch {
            val report = runCatchingCancellable { buildReport() }.getOrNull()
            val ok = report != null && ctx.copyDiagnostics(report)
            snackbar.showSnackbar(if (ok) copiedFmt.format(report!!.length / 1024) else copyFailed)
        }
    }) {
        Icon(Icons.Outlined.BugReport, contentDescription = stringResource(R.string.diag_copy_quick))
    }
}

/** Header (everything before the log) plus the log's last 150 lines. */
private fun preview(report: String): String {
    val cut = report.indexOf("\n## Log")
    if (cut < 0) return report.takeLast(20_000)
    val log = report.substring(cut + 1).lines()
    return report.substring(0, cut + 1) + log.first() + "\n…\n" + log.drop(1).takeLast(150).joinToString("\n")
}
