package app.slurp.data

import android.content.Context
import app.slurp.model.Quality

/**
 * Plain SharedPreferences. Nothing here is a secret — no tokens, no cookies —
 * so there is no reason to reach for EncryptedSharedPreferences and its
 * dependency.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("slurp", Context.MODE_PRIVATE)

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString(KEY_QUALITY, null) ?: "") }
            .getOrDefault(Quality.P1080)
        set(value) = sp.edit().putString(KEY_QUALITY, value.name).apply()

    /**
     * When true, a link arriving via the share sheet starts downloading with no
     * confirmation. This is the "instantly downloads it" behaviour and it is on
     * by default; the format picker is still there for when it matters.
     */
    var oneTap: Boolean
        get() = sp.getBoolean(KEY_ONE_TAP, true)
        set(value) = sp.edit().putBoolean(KEY_ONE_TAP, value).apply()

    /** Last time the bundled yt-dlp was updated, epoch millis. */
    var lastEngineUpdate: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE, value).apply()

    private companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_ONE_TAP = "one_tap"
        const val KEY_LAST_UPDATE = "last_engine_update"
    }
}
