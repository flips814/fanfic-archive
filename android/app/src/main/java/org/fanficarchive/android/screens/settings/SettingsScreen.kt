package org.fanficarchive.android.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerInfo
import org.fanficarchive.android.data.ServerProfile
import org.fanficarchive.android.data.StoryNotification
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.FanficCard
import org.fanficarchive.android.screens.components.FilterTag
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.MetaChip
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.components.SecondaryButton
import org.fanficarchive.android.screens.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: ArchiveRepository,
    profile: ServerProfile,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onDisconnected: () -> Unit,
    onOpenWork: (Int) -> Unit = {},
) {
    var items by remember { mutableStateOf<List<StoryNotification>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var serverInfo by remember { mutableStateOf<ServerInfo?>(null) }
    val readingProgress by repository.readingProgressForCurrentServer()
        .collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val siteUrl = serverInfo?.baseUrl?.takeIf { it.isNotBlank() } ?: profile.baseUrl
    val siteName = serverInfo?.siteName?.takeIf { it.isNotBlank() } ?: profile.siteName

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.refreshInfo() }
                .onSuccess { serverInfo = it }
            runCatching { repository.listNotifications() }
                .onSuccess { items = it.notifications }
                .onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val localProgressNotes = readingProgress.entries
        .sortedByDescending { it.value.updatedAt }
        .take(8)
        .map { (workId, progress) ->
            StoryNotification(
                id = "reading:$workId:${progress.updatedAt}",
                kind = "progress",
                title = "Reading progress",
                body = "You left off at chapter ${progress.chapterNumber}.",
                workId = workId,
                workTitle = items.find { it.workId == workId }?.workTitle,
                createdAt = java.time.Instant.ofEpochMilli(progress.updatedAt).toString(),
            )
        }

    val merged = (items + localProgressNotes)
        .distinctBy { it.id }
        .sortedByDescending { it.createdAt.orEmpty() }
        .filter { filter.isBlank() || it.kind == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings")
                        Text(
                            "Story notifications",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleLarge)
                    Text(siteName.ifBlank { "Fanfic Archive" }, style = MaterialTheme.typography.bodyLarge)
                    if (profile.username.isNotBlank()) {
                        Text("Signed in as ${profile.username}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Reading settings live on each chapter. Channel destinations (email / Discord) stay on the website.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                FanficCard {
                    Text("Server site", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The archive this app is connected to.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        siteName.ifBlank { "Fanfic Archive" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    SelectionContainer {
                        Text(
                            siteUrl,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        MetaChip(
                            text = "API v${serverInfo?.apiVersion ?: "—"}",
                            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                            background = MaterialTheme.colorScheme.surfaceVariant,
                            border = null,
                        )
                        if (profile.allowHttp) {
                            MetaChip(text = "HTTP allowed")
                        }
                    }
                    SecondaryButton(
                        onClick = {
                            val uri = Uri.parse(siteUrl.trimEnd('/') + "/")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        enabled = siteUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) { ButtonLabel("Open site in browser") }
                }
            }

            item {
                Text("Notifications", style = MaterialTheme.typography.titleLarge)
                Text(
                    "New stories, updates, comments, follows, and import progress.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterTag(label = "All", selected = filter == "", onClick = { filter = "" })
                    FilterTag(label = "Added", selected = filter == "story_added", onClick = { filter = "story_added" })
                    FilterTag(label = "Updates", selected = filter == "story_updated", onClick = { filter = "story_updated" })
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    FilterTag(label = "Comments", selected = filter == "comment", onClick = { filter = "comment" })
                    FilterTag(label = "Following", selected = filter == "following", onClick = { filter = "following" })
                    FilterTag(label = "Progress", selected = filter == "progress", onClick = { filter = "progress" })
                }
            }

            when {
                loading && items.isEmpty() -> item { LoadingBox() }
                error != null && items.isEmpty() -> item { ErrorText(error!!) }
                merged.isEmpty() -> item {
                    EmptyState(
                        "No story notifications yet",
                        "Imports, chapter updates, comments, and follows will show up here.",
                    )
                }
                else -> {
                    items(merged, key = { it.id }) { note ->
                        NotificationCard(note = note, onOpenWork = onOpenWork)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    error?.takeIf { items.isNotEmpty() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    PrimaryButton(
                        onClick = {
                            scope.launch {
                                repository.logout()
                                onLoggedOut()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonLabel("Log out") }
                    SecondaryButton(
                        onClick = {
                            scope.launch {
                                repository.disconnect()
                                onDisconnected()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { ButtonLabel("Disconnect server") }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(note: StoryNotification, onOpenWork: (Int) -> Unit) {
    FanficCard(
        onClick = note.workId?.let { id -> { onOpenWork(id) } },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetaChip(
                text = kindLabel(note.kind),
                foreground = MaterialTheme.colorScheme.primary,
                background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                border = MaterialTheme.colorScheme.primary,
            )
            note.status?.takeIf { it.isNotBlank() }?.let {
                MetaChip(text = it.replaceFirstChar { c -> c.uppercase() })
            }
        }
        Text(note.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            note.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            buildString {
                note.workTitle?.let { append(it); append(" · ") }
                append(relativeTime(note.createdAt))
                if (note.chaptersAdded > 0) append(" · +${note.chaptersAdded} chapters")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "story_added" -> "New story"
    "story_updated" -> "Updated"
    "comment" -> "Comment"
    "following" -> "Following"
    "progress" -> "Progress"
    else -> kind.replaceFirstChar { it.uppercase() }
}
