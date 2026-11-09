package io.github.ddastoor.kindlify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.ddastoor.kindlify.ui.KindlifyApp
import io.github.ddastoor.kindlify.ui.KindlifyTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** A launcher shortcut waiting for the window to get focus (needed to read the clipboard). */
    private var pendingShortcut: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: after rotation or process restore it was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            KindlifyTheme {
                KindlifyApp(vm)
            }
        }
    }

    /** A share or shortcut arriving while Kindlify is already open (launchMode is singleTask). */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) runPendingShortcut()
    }

    override fun onStop() {
        super.onStop()
        vm.flush()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_SHARE_HANDLED, false)) return
        // Reopening from Recents replays the original intent; it was already handled.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            ACTION_PASTE_INTO_LATEST, ACTION_PASTE_INTO_NEW -> {
                intent.putExtra(EXTRA_SHARE_HANDLED, true)
                pendingShortcut = intent.action
                if (hasWindowFocus()) runPendingShortcut()
            }
            else -> if (vm.receiveShare(intent)) intent.putExtra(EXTRA_SHARE_HANDLED, true)
        }
    }

    private fun runPendingShortcut() {
        val action = pendingShortcut ?: return
        pendingShortcut = null
        vm.pasteFromShortcut(intoNewCanvas = action == ACTION_PASTE_INTO_NEW)
    }

    companion object {
        /** Marks a share or shortcut intent as handled, so it never runs twice. */
        const val EXTRA_SHARE_HANDLED = "io.github.ddastoor.kindlify.SHARE_HANDLED"

        /** Launcher shortcuts (res/xml/shortcuts.xml). */
        const val ACTION_PASTE_INTO_LATEST = "io.github.ddastoor.kindlify.action.PASTE_INTO_LATEST"
        const val ACTION_PASTE_INTO_NEW = "io.github.ddastoor.kindlify.action.PASTE_INTO_NEW"
    }
}
