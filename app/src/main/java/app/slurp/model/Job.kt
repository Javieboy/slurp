package app.slurp.model

import app.slurp.core.Site
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class JobState {
    CHECKING, QUEUED, DOWNLOADING, SAVING, DONE, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/**
 * Serializable because the queue is written to disk on every change. Enums are
 * stored by name, so reordering [JobState], [Quality] or [Site] is safe but
 * renaming a constant will orphan jobs saved under the old name.
 */
@Serializable
data class Job(
    val id: String,
    val url: String,
    val title: String,
    val site: Site,
    val quality: Quality,
    val state: JobState = JobState.QUEUED,
    /**
     * 0f..1f, or -1f when yt-dlp has not reported a percentage yet.
     *
     * Transient, along with [etaSeconds] and [status]. These three change about
     * ten times a second for the whole of a download, and the queue is saved
     * from a flow of every change — persisting them meant rewriting the entire
     * file continuously while nothing durable had moved. Nothing is lost:
     * `QueueStore.load` resets all three anyway, because a restored job has no
     * running process to report progress for.
     */
    @Transient val progress: Float = -1f,
    @Transient val etaSeconds: Long = -1,
    /** Last line of yt-dlp output, shown small under the title while running. */
    @Transient val status: String = "",
    val savedAs: String? = null,
    /** MediaStore URI of the finished file, for Play and Open folder. */
    val savedUri: String? = null,
    /** Where it landed, e.g. "Movies/slurp" — shown when there is no file browser to open. */
    val savedIn: String? = null,
    val error: String? = null,
    /** Plain-language advice for [error], when there is any worth giving. */
    val hint: String? = null,
    /**
     * Set once the queue has already updated the engine and requeued this job.
     * Without it a job whose failure survives the update retries forever.
     */
    val engineRetried: Boolean = false,
    /** "3 / 12" when this job came from a playlist, null otherwise. */
    val batchLabel: String? = null,
    /** Poster image from the probe, shown on the card. */
    val thumbnail: String? = null,
) {
    val processId: String get() = "slurp-$id"
}
