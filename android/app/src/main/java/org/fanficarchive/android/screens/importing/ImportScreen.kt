package org.fanficarchive.android.screens.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.fanficarchive.android.data.VISIBILITY_PRIVATE
import org.fanficarchive.android.data.VISIBILITY_PUBLIC
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.FanficCard
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.FilterTag
import org.fanficarchive.android.screens.components.JobStatusPill
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.components.PrivatePill
import org.fanficarchive.android.screens.theme.ResponsivePane
import org.fanficarchive.android.screens.theme.rememberContentLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    repository: ArchiveRepository,
    onBack: (() -> Unit)? = null,
) {
    var urlsText by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(VISIBILITY_PUBLIC) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ImportResponse?>(null) }
    val layout = rememberContentLayout()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(result) {
        val response = result ?: return@LaunchedEffect
        val count = response.jobs.size
        snackbarHostState.showSnackbar(
            "Queued $count job${if (count == 1) "" else "s"}" +
                if (visibility == VISIBILITY_PRIVATE) " as private" else "",
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Add works", style = MaterialTheme.typography.titleLarge)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ResponsivePane(layout = layout, modifier = Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(layout.contentPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Supported sites match your server: AO3, FanFiction.net, Wattpad, Tumblr, and other FanFicFare sites. New imports are followed automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FanficTextField(
                value = urlsText,
                onValueChange = { urlsText = it; error = null },
                label = "URLs",
                placeholder = "https://archiveofourown.org/works/…",
                singleLine = false,
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Visibility", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies to every URL in this import.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterTag(
                    label = "Public",
                    selected = visibility == VISIBILITY_PUBLIC,
                    onClick = { visibility = VISIBILITY_PUBLIC },
                )
                FilterTag(
                    label = "Private",
                    selected = visibility == VISIBILITY_PRIVATE,
                    onClick = { visibility = VISIBILITY_PRIVATE },
                )
            }
            Text(
                if (visibility == VISIBILITY_PRIVATE) {
                    "Only you can find and read these stories."
                } else {
                    "Everyone on this server can find and read these stories."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        runCatching { repository.importUrls(urls, visibility) }
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
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            result?.let { response ->
                response.jobs.forEach { job ->
                    FanficCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            JobStatusPill(job.status)
                            if (job.isPrivate) PrivatePill()
                        }
                        Text(
                            job.url.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (response.rejected.isNotEmpty()) {
                    Text("Rejected", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    response.rejected.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        }
    }
}
