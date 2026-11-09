package dev.jdcm.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.jdcm.container
import dev.jdcm.diag.DiagLog

class MainActivity : ComponentActivity() {
    /** A notification's match to open, consumed once by the root. */
    private val pendingOpen = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        setContent { JdcmRoot(container, pendingOpen) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        val group = i?.getIntExtra(EXTRA_GROUP, 0) ?: 0
        val match = i?.getIntExtra(EXTRA_MATCH, 0) ?: 0
        if (group > 0 && match > 0) {
            DiagLog.i("UI", "opened from notification match=$match")
            pendingOpen.value = OpenRequest(group, match, i?.getStringExtra(EXTRA_KID))
        }
    }

    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_MATCH = "match"
        const val EXTRA_KID = "kid"
    }
}

data class OpenRequest(val groupId: Int, val matchId: Int, val kidId: String?)
