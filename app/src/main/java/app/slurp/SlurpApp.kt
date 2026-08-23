package app.slurp

import android.app.Application
import app.slurp.download.DownloadQueue
import app.slurp.engine.Ytdlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SlurpApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DownloadQueue.attach(this)

        // Unpacking the Python runtime takes a few seconds on first launch.
        // Starting it here means it is usually finished by the time the user
        // has pasted a link, instead of stalling the first download.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Ytdlp.ensureInit(this@SlurpApp)
        }
    }
}
