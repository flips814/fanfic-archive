package org.fanficarchive.android.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import org.fanficarchive.android.data.WorkSummary
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: ArchiveRepository,
    siteName: String,
    onOpenWork: (Int) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var followedOnly by remember { mutableStateOf(false) }
    var works by remember { mutableStateOf<List<WorkSummary>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.listWorks(query, followedOnly) }
                .onSuccess {
                    works = it.works
                    total = it.total
                }
                .onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(followedOnly) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(siteName.ifBlank { "Library" }) },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onImport) {
                        Icon(Icons.Default.Add, contentDescription = "Import")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search title or author") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                trailingIcon = {
                    Text(
                        "Go",
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { refresh() },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
            FilterChip(
                selected = followedOnly,
                onClick = { followedOnly = !followedOnly },
                label = { Text("Following") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            when {
                loading -> LoadingBox()
                error != null -> ErrorText(error!!)
                works.isEmpty() -> EmptyState("No works yet", "Import a story URL, or clear your filters.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "$total work${if (total == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(works, key = { it.id }) { work ->
                        WorkRow(work = work, onClick = { onOpenWork(work.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkRow(work: WorkSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(work.title, style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(
                work.author,
                work.site,
                work.status,
                "${work.chapters} ch",
                work.wordCount?.let { "%,d words".format(it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (work.isFollowing) {
            Text("Following", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
