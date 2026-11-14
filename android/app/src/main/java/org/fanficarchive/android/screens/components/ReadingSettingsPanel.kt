package org.fanficarchive.android.screens.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fanficarchive.android.data.ReaderPrefs

private val ReaderThemes = listOf(
    "sepia" to "Sepia",
    "light" to "Light",
    "dark" to "Dark",
    "high" to "High contrast",
    "site" to "Site theme",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingSettingsPanel(
    prefs: ReaderPrefs,
    onChange: (fontScale: Float, theme: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reading settings", style = MaterialTheme.typography.titleMedium)
                    if (!open) {
                        Text(
                            "${"%.0f".format(prefs.fontScale * 100)}% · ${ReaderThemes.find { it.first == prefs.theme }?.second ?: prefs.theme}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (open) "Hide reading settings" else "Show reading settings",
                )
            }

            if (open) {
                Text("Text size: ${"%.0f".format(prefs.fontScale * 100)}%")
                Slider(
                    value = prefs.fontScale,
                    onValueChange = { onChange(it, prefs.theme) },
                    valueRange = 0.7f..2.0f,
                )
                Text("Page colour", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReaderThemes.forEach { (id, label) ->
                        FilterTag(
                            label = label,
                            selected = prefs.theme == id,
                            onClick = { onChange(prefs.fontScale, id) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(onClick = { onChange(1.1f, "sepia") }) {
                        ButtonLabel("Reset")
                    }
                    PrimaryButton(onClick = { open = false }) {
                        ButtonLabel("Done")
                    }
                }
                Text(
                    "Saved on this device for every chapter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
