package app.slurp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.slurp.data.Prefs
import app.slurp.data.VideoRoot
import app.slurp.update.AppUpdater

/**
 * Settings, such as they are.
 *
 * The download location is a folder *name* under a standard media collection,
 * not a free path. Scoped storage only lets MediaStore file things under its
 * own collections, and going outside that means SAF — a tree URI, a different
 * write path, and files that no longer show up in the gallery. Naming the
 * folder covers what people actually want without giving that up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(prefs: Prefs, onDismiss: () -> Unit) {
    var folder by remember { mutableStateOf(prefs.folderName) }
    var root by remember { mutableStateOf(prefs.videoRoot) }
    var oneTap by remember { mutableStateOf(prefs.oneTap) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        confirmButton = {
            TextButton(onClick = {
                prefs.folderName = folder
                prefs.videoRoot = root
                prefs.oneTap = oneTap
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                Text("Download location", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder name") },
                    singleLine = true,
                )

                Text("Video goes in", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoRoot.entries.forEach { option ->
                        FilterChip(
                            selected = root == option,
                            onClick = { root = option },
                            label = { Text(option.label) },
                        )
                    }
                }

                val shown = Prefs.sanitiseFolder(folder)
                Text(
                    "Video → ${root.directory}/$shown\nAudio → Music/$shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (root == VideoRoot.DOWNLOAD) {
                        "Audio always goes to Music, which is where players look " +
                            "for it. Download is filed with your downloads rather " +
                            "than your gallery, so video there shows up in Files, " +
                            "not Photos."
                    } else {
                        "Audio always goes to Music, which is where players look for it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.padding(end = 12.dp)) {
                        Text("Start shared links at once", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Off puts the link in the box instead",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = oneTap, onCheckedChange = { oneTap = it })
                }

                // Here so a bug report can name a version. Nobody knows which
                // build they are on otherwise, and it is the first thing any
                // report needs.
                HorizontalDivider()
                Text(
                    "slurp ${AppUpdater.installedVersion(LocalContext.current) ?: "unknown version"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
