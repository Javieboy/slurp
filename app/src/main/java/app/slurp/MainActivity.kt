package app.slurp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.slurp.core.UrlSniffer
import app.slurp.data.Prefs
import app.slurp.download.DownloadQueue
import app.slurp.ui.HomeScreen
import app.slurp.ui.SlurpTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var pendingLink by mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = Prefs(this)
        DownloadQueue.attach(this)
        askForNotifications()
        handleIntent(intent)

        setContent {
            SlurpTheme {
                HomeScreen(
                    prefs = prefs,
                    pendingLink = pendingLink,
                    onLinkConsumed = { pendingLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The share-sheet path. This is how the app is meant to be used: you are
     * inside TikTok, you hit Share, you pick slurp, and it is already
     * downloading before the sheet has finished animating away.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = UrlSniffer.firstUrl(shared)

        if (url == null) {
            Toast.makeText(this, "No link in that share", Toast.LENGTH_SHORT).show()
            return
        }

        // Consume the extra so a configuration change does not re-queue it.
        intent.removeExtra(Intent.EXTRA_TEXT)

        if (prefs.oneTap) {
            DownloadQueue.submit(url, prefs.quality)
            Toast.makeText(this, "Queued", Toast.LENGTH_SHORT).show()
        } else {
            pendingLink = url
        }
    }

    /**
     * Without this the foreground-service notification is silently suppressed
     * on Android 13+, and downloads look like they are doing nothing.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
