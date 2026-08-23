package app.slurp.model

enum class Quality(val label: String, val blurb: String) {
    BEST("Best", "Highest the site offers"),
    P1080("1080p", "Good default"),
    P720("720p", "Smaller files"),
    AUDIO("Audio", "m4a, no video");

    val isAudio: Boolean get() = this == AUDIO
}
