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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: after rotation or process restore the share was already handled.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            KindlifyTheme {
                KindlifyApp(vm)
            }
        }
    }

    /** A share arriving while Kindlify is already open (launchMode is singleTask). */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStop() {
        super.onStop()
        vm.flush()
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_SHARE_HANDLED, false)) return
        // Reopening from Recents replays the original intent; that share was already handled.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (vm.receiveShare(intent)) intent.putExtra(EXTRA_SHARE_HANDLED, true)
    }

    companion object {
        const val EXTRA_SHARE_HANDLED = "io.github.ddastoor.kindlify.SHARE_HANDLED"
    }
}
