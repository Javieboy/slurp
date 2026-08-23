package app.slurp.ui

import android.content.ClipboardManager
import android.content.Context

/**
 * Reads the clipboard through the framework rather than Compose's
 * LocalClipboardManager, which has churned through three different shapes
 * across recent Compose releases. This one has been stable since API 11.
 *
 * Returns null on Android 10+ when the app is not focused — the system blocks
 * background clipboard reads, which is why slurp never polls it silently.
 */
fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}
