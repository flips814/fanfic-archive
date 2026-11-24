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
    baseUrl: String = "",
    onOpenWork: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("") }
    var followedOnly by remember { mutableStateOf(false) }
    var downloadedOnly by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var sites by remember { mutableStateOf<List<String>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var relationships by remember { mutableStateOf<List<String>>(emptyList()) }
    var works by remember { mutableStateOf<List<WorkSummary>>(emptyList()) }
    var downloadedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
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
            downloadedIds = repository.downloadedWorkIds()
            if (!downloadedOnly) {
                runCatching { repository.syncReadingProgress() }
            }
            if (downloadedOnly) {
                var offline = repository.listOfflineWorks()
                if (query.isNotBlank()) {
                    val q = query.trim().lowercase()
                    offline = offline.filter {
                        it.title.lowercase().contains(q) || (it.author?.lowercase()?.contains(q) == true)
                    }
                }
                if (visibility == VISIBILITY_PUBLIC) {
                    offline = offline.filter { !it.isPrivate }
                } else if (visibility == VISIBILITY_PRIVATE) {
                    offline = offline.filter { it.isPrivate }
                }
                if (status == "complete") {
                    offline = offline.filter { it.status?.lowercase()?.startsWith("complete") == true }
                } else if (status == "ongoing") {
                    offline = offline.filter { it.status?.lowercase()?.startsWith("complete") != true }
                }
                if (site.isNotBlank()) {
                    offline = offline.filter { it.site == site }
                }
                if (category.isNotBlank()) {
                    offline = offline.filter { category in it.fandoms }
                }
                if (tag.isNotBlank()) {
                    offline = offline.filter { tag in it.tags }
                }
                if (relationship.isNotBlank()) {
                    offline = offline.filter { relationship in it.relationships }
                }
                works = offline
                total = offline.size
                loading = false
                return@launch
            }
            runCatching {
                repository.listWorks(
                    query = query,
                    site = site.ifBlank { null },
                    category = category.ifBlank { null },
                    tag = tag.ifBlank { null },
                    relationship = relationship.ifBlank { null },
                    status = status.ifBlank { null },
                    visibility = visibility.ifBlank { null },
                    followedOnly = followedOnly,
                )
            }.onSuccess {
                works = it.works
                total = it.total
                if (it.sites.isNotEmpty()) sites = it.sites
                if (it.categories.isNotEmpty()) categories = it.categories
                if (it.tags.isNotEmpty()) tags = it.tags
                if (it.relationships.isNotEmpty()) relationships = it.relationships
            }.onFailure { err ->
                // If the server is down, still show local downloads.
                val offline = repository.listOfflineWorks()
                if (offline.isNotEmpty()) {
                    works = offline
                    total = offline.size
                    error = null
                    downloadedOnly = true
                } else {
                    error = repository.errorMessage(err)
                }
            }
            loading = false
        }
    }

    LaunchedEffect(site, category, tag, relationship, status, visibility, followedOnly, downloadedOnly) { refresh() }

    val activeFilters = listOfNotNull(
        site.ifBlank { null },
        category.ifBlank { null },
        tag.ifBlank { null },
        relationship.ifBlank { null },
        status.ifBlank { null },
        visibility.ifBlank { null },
        if (followedOnly) "following" else null,
        if (downloadedOnly) "downloaded" else null,
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
                            onClick = {
                                followedOnly = !followedOnly
                                if (followedOnly) downloadedOnly = false
                            },
                        )
                        FilterTag(
                            label = "Downloaded",
                            selected = downloadedOnly,
                            onClick = {
                                downloadedOnly = !downloadedOnly
                                if (downloadedOnly) followedOnly = false
                            },
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
                    if (categories.isNotEmpty() || category.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        val options = if (category.isNotBlank() && category !in categories) {
                            listOf(category) + categories
                        } else {
                            categories
                        }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            FanficTextField(
                                value = if (category.isBlank()) "All categories" else category,
                                onValueChange = {},
                                readOnly = true,
                                label = "Category",
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                textFieldModifier = Modifier.menuAnchor(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("All categories") },
                                    onClick = { category = ""; expanded = false },
                                )
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { category = option; expanded = false },
                                    )
                                }
                            }
                        }
                    }
                    if (tags.isNotEmpty() || tag.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        val options = if (tag.isNotBlank() && tag !in tags) {
                            listOf(tag) + tags
                        } else {
                            tags
                        }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            FanficTextField(
                                value = if (tag.isBlank()) "All tags" else tag,
                                onValueChange = {},
                                readOnly = true,
                                label = "Tag",
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                textFieldModifier = Modifier.menuAnchor(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("All tags") },
                                    onClick = { tag = ""; expanded = false },
                                )
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { tag = option; expanded = false },
                                    )
                                }
                            }
                        }
                    }
                    if (relationships.isNotEmpty() || relationship.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        val options = if (relationship.isNotBlank() && relationship !in relationships) {
                            listOf(relationship) + relationships
                        } else {
                            relationships
                        }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            FanficTextField(
                                value = if (relationship.isBlank()) "All relationships" else relationship,
                                onValueChange = {},
                                readOnly = true,
                                label = "Relationship",
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                textFieldModifier = Modifier.menuAnchor(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("All relationships") },
                                    onClick = { relationship = ""; expanded = false },
                                )
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { relationship = option; expanded = false },
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
                    if (downloadedOnly) "No downloads yet" else "No works yet",
                    if (downloadedOnly) {
                        "Open a work and tap Download for offline."
                    } else if (query.isNotBlank() || activeFilters > 0) {
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
                            resumeChapter = readingProgress[work.id]?.chapterNumber
                                ?: work.resumeChapter,
                            downloaded = work.id in downloadedIds || downloadedOnly,
                            baseUrl = baseUrl,
                        )
                    }
                }
            }
        }
    }
}
