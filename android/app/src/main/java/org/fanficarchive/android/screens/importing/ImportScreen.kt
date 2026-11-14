package org.fanficarchive.android.screens.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ImportResponse
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.JobStatusPill
import org.fanficarchive.android.screens.components.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    repository: ArchiveRepository,
    onBack: (() -> Unit)? = null,
) {
    var urlsText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ImportResponse?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Add works")
                        Text(
                            "Paste story URLs, one per line",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Supported sites match your server: AO3, FanFiction.net, Wattpad, Tumblr, and other FanFicFare sites. New imports are followed automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = urlsText,
                onValueChange = { urlsText = it; error = null },
                label = { Text("URLs") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )
            PrimaryButton(
                onClick = {
                    val urls = urlsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (urls.isEmpty()) {
                        error = "Add at least one URL."
                        return@PrimaryButton
                    }
                    scope.launch {
                        loading = true
                        error = null
                        result = null
                        runCatching { repository.importUrls(urls) }
                            .onSuccess { result = it }
                            .onFailure { error = repository.errorMessage(it) }
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonLabel(if (loading) "Queuing…" else "Queue imports")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { response ->
                Text(
                    "Queued ${response.jobs.size} job${if (response.jobs.size == 1) "" else "s"}.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
                response.jobs.forEach { job ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        JobStatusPill(job.status)
                        Text(job.url.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (response.rejected.isNotEmpty()) {
                    Text("Rejected:", color = MaterialTheme.colorScheme.error)
                    response.rejected.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
