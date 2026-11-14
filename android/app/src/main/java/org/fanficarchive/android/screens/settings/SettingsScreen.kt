package org.fanficarchive.android.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: ArchiveRepository,
    profile: ServerProfile,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onDisconnected: () -> Unit,
) {
    val readerPrefs by repository.readerPrefs.collectAsState(
        initial = org.fanficarchive.android.data.ReaderPrefs(),
    )
    val scope = rememberCoroutineScope()
    val themes = listOf("sepia", "light", "dark", "high")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleLarge)
            Text(profile.siteName.ifBlank { "Fanfic Archive" }, style = MaterialTheme.typography.bodyLarge)
            Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.username.isNotBlank()) {
                Text("Signed in as ${profile.username}", style = MaterialTheme.typography.bodyMedium)
            }

            Text("Reader", style = MaterialTheme.typography.titleLarge)
            Text("Text size: ${"%.1f".format(readerPrefs.fontScale)}×")
            Slider(
                value = readerPrefs.fontScale,
                onValueChange = { scale ->
                    scope.launch { repository.saveReaderPrefs(scale, readerPrefs.theme) }
                },
                valueRange = 0.8f..1.8f,
            )
            Text("Page color")
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                themes.forEach { theme ->
                    FilterChip(
                        selected = readerPrefs.theme == theme,
                        onClick = {
                            scope.launch { repository.saveReaderPrefs(readerPrefs.fontScale, theme) }
                        },
                        label = { Text(theme.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        repository.logout()
                        onLoggedOut()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        repository.disconnect()
                        onDisconnected()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect server")
            }
        }
    }
}
