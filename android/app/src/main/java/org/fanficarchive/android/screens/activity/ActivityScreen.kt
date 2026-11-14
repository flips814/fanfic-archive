package org.fanficarchive.android.screens.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import org.fanficarchive.android.data.Job
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.FanficCard
import org.fanficarchive.android.screens.components.FilterTag
import org.fanficarchive.android.screens.components.JobStatusPill
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    repository: ArchiveRepository,
    onOpenWork: (Int) -> Unit,
) {
    var kind by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                repository.listJobs(
                    kind = kind.ifBlank { null },
                    status = status.ifBlank { null },
                )
            }.onSuccess {
                jobs = it.jobs
                total = it.total
            }.onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(kind, status) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Activity")
                        Text(
                            "$total job${if (total == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterTag(label = "All", selected = kind == "", onClick = { kind = "" })
                FilterTag(label = "Imports", selected = kind == "import", onClick = { kind = "import" })
                FilterTag(label = "Updates", selected = kind == "update", onClick = { kind = "update" })
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("" to "Any", "done" to "Done", "failed" to "Failed", "running" to "Running", "queued" to "Queued")
                    .forEach { (value, label) ->
                        FilterTag(
                            label = label,
                            selected = status == value,
                            onClick = { status = value },
                        )
                    }
            }
            when {
                loading -> LoadingBox()
                error != null -> ErrorText(error!!)
                jobs.isEmpty() -> EmptyState("No activity yet", "Imports and update checks will show up here.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(jobs, key = { it.id }) { job ->
                        FanficCard(
                            onClick = job.workId?.let { id -> { onOpenWork(id) } },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                JobStatusPill(job.status)
                                Text(
                                    job.kind.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                job.workTitle ?: job.url ?: "Job #${job.id}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                buildString {
                                    append(job.requestedBy?.name ?: "Auto-updater")
                                    append(" · ")
                                    append(relativeTime(job.createdAt))
                                    if (job.chaptersAdded > 0) append(" · +${job.chaptersAdded} chapters")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            job.message?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (job.status == "failed") {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
