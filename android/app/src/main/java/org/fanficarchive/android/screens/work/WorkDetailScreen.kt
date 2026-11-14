package org.fanficarchive.android.screens.work

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.fanficarchive.android.data.WorkDetail
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.HtmlPane
import org.fanficarchive.android.screens.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDetailScreen(
    repository: ArchiveRepository,
    workId: Int,
    baseUrl: String,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    var work by remember { mutableStateOf<WorkDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.getWork(workId) }
                .onSuccess { work = it }
                .onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(workId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(work?.title ?: "Work") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading && work == null -> LoadingBox(Modifier.padding(padding))
            error != null && work == null -> ErrorText(error!!, Modifier.padding(padding))
            work != null -> {
                val detail = work!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(detail.author, detail.site, detail.status, detail.rating)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detail.addedBy?.let {
                        Text("Imported by ${it.name}", style = MaterialTheme.typography.bodyMedium)
                    }
                    detail.lastError?.let {
                        Text("Update error: $it", color = MaterialTheme.colorScheme.error)
                    }
                    TagBlock("Fandoms", detail.fandoms)
                    TagBlock("Tags", detail.tags)
                    TagBlock("Warnings", detail.warnings)
                    if (!detail.summaryHtml.isNullOrBlank()) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium)
                        HtmlPane(html = detail.summaryHtml, baseUrl = baseUrl, fontScale = 1f, theme = "light")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { repository.setFollowing(workId, !detail.isFollowing) }
                                        .onSuccess { work = detail.copy(isFollowing = it.isFollowing) }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                        ) {
                            Text(if (detail.isFollowing) "Unfollow" else "Follow")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { repository.checkUpdates(workId) }
                                        .onSuccess { statusMessage = "Update check queued (${it.status})" }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                        ) {
                            Text("Check updates")
                        }
                    }
                    statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Chapters", style = MaterialTheme.typography.titleMedium)
                    detail.chapterList.forEach { chapter ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChapter(chapter.number) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text("${chapter.number}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    chapter.wordCount?.let { "%,d words".format(it) },
                                    chapter.importedBy?.name?.let { "by $it" },
                                    if (chapter.removedFromSource) "removed from source" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagBlock(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(values.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}
