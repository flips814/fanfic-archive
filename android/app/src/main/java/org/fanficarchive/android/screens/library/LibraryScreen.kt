package org.fanficarchive.android.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.VISIBILITY_PRIVATE
import org.fanficarchive.android.data.VISIBILITY_PUBLIC
import org.fanficarchive.android.data.WorkSummary
import org.fanficarchive.android.data.labelsMatch
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.FilterTag
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.WorkCard
import org.fanficarchive.android.screens.theme.ResponsivePane
import org.fanficarchive.android.screens.theme.rememberContentLayout
import org.fanficarchive.android.screens.theme.siteLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    repository: ArchiveRepository,
    siteName: String,
    baseUrl: String = "",
    onOpenWork: (Int) -> Unit,
    onSettings: () -> Unit,
    onAddWorks: () -> Unit = {},
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
    val layout = rememberContentLayout()
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
                    offline = offline.filter { labelsMatch(category, it.fandoms) }
                }
                if (tag.isNotBlank()) {
                    offline = offline.filter { labelsMatch(tag, it.tags) }
                }
                if (relationship.isNotBlank()) {
                    offline = offline.filter { labelsMatch(relationship, it.relationships) }
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
                        Text(siteName.ifBlank { "Library" }, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "$total work${if (total == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        BadgedBox(
                            badge = {
                                if (activeFilters > 0) {
                                    Badge { Text("$activeFilters") }
                                }
                            },
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filters")
                        }
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddWorks,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add works") },
            )
        },
    ) { padding ->
        ResponsivePane(layout = layout, modifier = Modifier.padding(padding)) {
            val filterWidth = if (layout.filterColumns == 1) 1f else 0.48f
            Column(modifier = Modifier.fillMaxSize()) {
                FanficTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search",
                    placeholder = "Title or author",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = layout.contentPadding, vertical = 8.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                )
                if (showFilters || activeFilters > 0) {
                    Column(
                        modifier = Modifier.padding(horizontal = layout.contentPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            maxItemsInEachRow = layout.filterColumns,
                        ) {
                            if (sites.isNotEmpty()) {
                                FilterDropdown(
                                    label = "Site",
                                    value = if (site.isBlank()) "All sites" else siteLabel(site),
                                    options = listOf("" to "All sites") + sites.map { it to siteLabel(it) },
                                    onSelect = { site = it },
                                    modifier = Modifier.fillMaxWidth(filterWidth),
                                )
                            }
                            if (categories.isNotEmpty() || category.isNotBlank()) {
                                val options = buildList {
                                    add("" to "All categories")
                                    if (category.isNotBlank() && category !in categories) add(category to category)
                                    categories.forEach { add(it to it) }
                                }
                                FilterDropdown(
                                    label = "Category",
                                    value = if (category.isBlank()) "All categories" else category,
                                    options = options,
                                    onSelect = { category = it },
                                    modifier = Modifier.fillMaxWidth(filterWidth),
                                )
                            }
                            if (tags.isNotEmpty() || tag.isNotBlank()) {
                                val options = buildList {
                                    add("" to "All tags")
                                    if (tag.isNotBlank() && tag !in tags) add(tag to tag)
                                    tags.forEach { add(it to it) }
                                }
                                FilterDropdown(
                                    label = "Tag",
                                    value = if (tag.isBlank()) "All tags" else tag,
                                    options = options,
                                    onSelect = { tag = it },
                                    modifier = Modifier.fillMaxWidth(filterWidth),
                                )
                            }
                            if (relationships.isNotEmpty() || relationship.isNotBlank()) {
                                val options = buildList {
                                    add("" to "All relationships")
                                    if (relationship.isNotBlank() && relationship !in relationships) {
                                        add(relationship to relationship)
                                    }
                                    relationships.forEach { add(it to it) }
                                }
                                FilterDropdown(
                                    label = "Relationship",
                                    value = if (relationship.isBlank()) "All relationships" else relationship,
                                    options = options,
                                    onSelect = { relationship = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
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
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(layout.libraryColumns),
                        contentPadding = PaddingValues(layout.contentPadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(works, key = { it.id }, span = { GridItemSpan(1) }) { work ->
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        FanficTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = label,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textFieldModifier = Modifier.menuAnchor(
                type = androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
