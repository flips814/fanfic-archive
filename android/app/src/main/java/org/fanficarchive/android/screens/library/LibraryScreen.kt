package org.fanficarchive.android.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.collectAsState
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.VISIBILITY_PRIVATE
import org.fanficarchive.android.data.VISIBILITY_PUBLIC
import org.fanficarchive.android.data.WorkSummary
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.FilterTag
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.QuietButton
import org.fanficarchive.android.screens.components.WorkCard
import org.fanficarchive.android.screens.theme.siteLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: ArchiveRepository,
    siteName: String,
    onOpenWork: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("") }
    var followedOnly by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf<List<String>>(emptyList()) }
    var works by remember { mutableStateOf<List<WorkSummary>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val readingProgress by repository.readingProgressForCurrentServer()
        .collectAsState(initial = emptyMap())

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching {
                repository.listWorks(
                    query = query,
                    site = site.ifBlank { null },
                    status = status.ifBlank { null },
                    visibility = visibility.ifBlank { null },
                    followedOnly = followedOnly,
                )
            }.onSuccess {
                works = it.works
                total = it.total
                if (it.sites.isNotEmpty()) sites = it.sites
            }.onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(site, status, visibility, followedOnly) { refresh() }

    val activeFilters = listOfNotNull(
        site.ifBlank { null },
        status.ifBlank { null },
        visibility.ifBlank { null },
        if (followedOnly) "following" else null,
    ).size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(siteName.ifBlank { "Library" })
                        Text(
                            "$total work${if (total == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filters")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
            FanficTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search",
                placeholder = "Title or author",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                trailingIcon = {
                    QuietButton(onClick = { refresh() }) { ButtonLabel("Search") }
                },
            )
            if (showFilters || activeFilters > 0) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterTag(
                            label = "Following",
                            selected = followedOnly,
                            onClick = { followedOnly = !followedOnly },
                        )
                        FilterTag(
                            label = "Complete",
                            selected = status == "complete",
                            onClick = { status = if (status == "complete") "" else "complete" },
                        )
                        FilterTag(
                            label = "In progress",
                            selected = status == "ongoing",
                            onClick = { status = if (status == "ongoing") "" else "ongoing" },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterTag(
                            label = "All visibility",
                            selected = visibility.isBlank(),
                            onClick = { visibility = "" },
                        )
                        FilterTag(
                            label = "Public",
                            selected = visibility == VISIBILITY_PUBLIC,
                            onClick = {
                                visibility = if (visibility == VISIBILITY_PUBLIC) "" else VISIBILITY_PUBLIC
                            },
                        )
                        FilterTag(
                            label = "Private",
                            selected = visibility == VISIBILITY_PRIVATE,
                            onClick = {
                                visibility = if (visibility == VISIBILITY_PRIVATE) "" else VISIBILITY_PRIVATE
                            },
                        )
                    }
                    if (sites.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            FanficTextField(
                                value = if (site.isBlank()) "All sites" else siteLabel(site),
                                onValueChange = {},
                                readOnly = true,
                                label = "Site",
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                textFieldModifier = Modifier.menuAnchor(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("All sites") }, onClick = { site = ""; expanded = false })
                                sites.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(siteLabel(option)) },
                                        onClick = { site = option; expanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            when {
                loading -> LoadingBox()
                error != null -> ErrorText(error!!)
                works.isEmpty() -> EmptyState(
                    "No works yet",
                    if (query.isNotBlank() || activeFilters > 0) {
                        "Nothing matched these filters."
                    } else {
                        "Import a story URL from Add works."
                    },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(works, key = { it.id }) { work ->
                        WorkCard(
                            work = work,
                            onClick = { onOpenWork(work.id) },
                            resumeChapter = readingProgress[work.id]?.chapterNumber,
                        )
                    }
                }
            }
        }
    }
}
