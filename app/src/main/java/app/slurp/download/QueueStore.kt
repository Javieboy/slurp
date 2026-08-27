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
     * DOWNLOADING and CHECKING requeue safely: the work directory died with the
     * process and nothing had reached the gallery, so starting again costs
     * bandwidth and nothing else.
     *
     * **SAVING does not.** That state spans `MediaStoreSink.publish`, which
     * copies the file into the gallery and only then marks the job DONE — so a
     * process killed inside it may well have written the file already, with only
     * the DONE state lost. Requeueing downloaded the whole thing a second time
     * and MediaStore, which will not overwrite, filed it as "name (1)". Two
     * copies of the same video, silently.
     *
     * Installing an app update is exactly this: the installer kills the running
     * process, so any download in SAVING at that moment hit it.
     *
     * There is no way from here to tell whether the write finished, so the job
     * stops and says so. A wasted retry the user chose beats a duplicate they
     * did not.
     */
    fun load(context: Context): List<Job> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        json.decodeFromString(serializer, f.readText()).map { job ->
            when (job.state) {
                JobState.DOWNLOADING, JobState.CHECKING ->
                    job.copy(
                        state = JobState.QUEUED,
                        progress = -1f,
                        etaSeconds = -1,
                        status = "Interrupted — will retry",
                    )
                JobState.SAVING ->
                    job.copy(
                        state = JobState.FAILED,
                        progress = -1f,
                        etaSeconds = -1,
                        status = "",
                        error = "Interrupted while saving",
                        hint = "This may already be in your gallery — check before " +
                            "retrying, or you will end up with two copies.",
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
