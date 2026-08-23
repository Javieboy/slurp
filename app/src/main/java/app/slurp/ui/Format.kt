package app.slurp.ui

/** Small formatting helpers shared by the job cards. */
object Format {

    fun eta(seconds: Long): String? {
        if (seconds <= 0) return null
        return when {
            seconds < 60 -> "${seconds}s left"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
        }
    }

    fun percent(progress: Float): String? =
        if (progress < 0f) null else "${(progress * 100).toInt()}%"
}
