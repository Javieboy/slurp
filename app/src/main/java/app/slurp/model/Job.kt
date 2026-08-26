package app.slurp.model

import app.slurp.core.Site

enum class JobState {
    CHECKING, QUEUED, DOWNLOADING, SAVING, DONE, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

data class Job(
    val id: String,
    val url: String,
    val title: String,
    val site: Site,
    val quality: Quality,
    val state: JobState = JobState.QUEUED,
    /** 0f..1f, or -1f when yt-dlp has not reported a percentage yet. */
    val progress: Float = -1f,
    val etaSeconds: Long = -1,
    /** Last line of yt-dlp output, shown small under the title while running. */
    val status: String = "",
    val savedAs: String? = null,
    val error: String? = null,
    /** Plain-language advice for [error], when there is any worth giving. */
    val hint: String? = null,
    /** "3 / 12" when this job came from a playlist, null otherwise. */
    val batchLabel: String? = null,
) {
    val processId: String get() = "slurp-$id"
}
