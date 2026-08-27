package app.slurp.download

import android.content.Context
import android.util.Log
import app.slurp.model.Job
import app.slurp.model.JobState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the queue on disk so it survives the process being killed.
 *
 * This is the oldest gap in the project, and playlists made it matter: queueing
 * forty videos that download one at a time means the app is alive for a long
 * while, and Android reclaims backgrounded processes freely. Losing the app used
 * to lose every job that had not finished.
 *
 * A plain JSON file rather than Room — the whole state is one list, written a
 * few times a minute at most, and Room would be a dependency and a schema for
 * something a file does exactly as well.
 */
object QueueStore {

    private const val TAG = "slurp/queue"
    private const val FILE = "queue.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Job.serializer())

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun save(context: Context, jobs: List<Job>) {
        runCatching {
            // Write to a temp file and rename. A process killed mid-write would
            // otherwise leave truncated JSON, and the next launch would drop the
            // whole queue rather than one job.
            val target = file(context)
            val tmp = File(target.parentFile, "$FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, jobs))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not save the queue", it) }
    }

    /**
     * Reads the queue back, repairing anything that was mid-flight.
     *
     * A job saved as DOWNLOADING or SAVING was interrupted by the process
     * dying: its part-files are gone with the work directory, so it goes back to
     * QUEUED to start again rather than pretending to still be running. CHECKING
     * is the same — the probe never finished.
     */
    fun load(context: Context): List<Job> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        json.decodeFromString(serializer, f.readText()).map { job ->
            when (job.state) {
                JobState.DOWNLOADING, JobState.SAVING, JobState.CHECKING ->
                    job.copy(
                        state = JobState.QUEUED,
                        progress = -1f,
                        etaSeconds = -1,
                        status = "Interrupted — will retry",
                    )
                else -> job
            }
        }
    }.getOrElse {
        // A corrupt file should cost the queue, not the app.
        Log.w(TAG, "could not read the queue, starting empty", it)
        emptyList()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
