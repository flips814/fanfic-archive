package org.fanficarchive.android.screens.users

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ArchiveUser
import org.fanficarchive.android.screens.components.EmptyState
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.FanficCard
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(repository: ArchiveRepository) {
    var users by remember { mutableStateOf<List<ArchiveUser>>(emptyList()) }
    var autoChapters by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.listUsers() }
                .onSuccess {
                    users = it.users
                    autoChapters = it.autoUpdaterChapters
                }
                .onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Users") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> LoadingBox(Modifier.padding(padding))
            error != null -> ErrorText(error!!, Modifier.padding(padding))
            users.isEmpty() -> EmptyState("No users yet", "People who sign in will appear here.", Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(users, key = { it.id }) { user ->
                    FanficCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    user.name.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    buildString {
                                        append(user.name)
                                        if (user.isAdmin) append(" · admin")
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                user.email?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(
                            "${user.worksImported} works · ${user.chaptersImported} chapters · ${user.following} following",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            "Joined ${relativeTime(user.createdAt)} · last login ${relativeTime(user.lastLoginAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item {
                    FanficCard {
                        Text("Auto-updater", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$autoChapters chapters imported by scheduled checks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
