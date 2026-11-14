package org.fanficarchive.android.screens.settings

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.NotificationSettings
import org.fanficarchive.android.data.ServerProfile
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.components.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: ArchiveRepository,
    profile: ServerProfile,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onDisconnected: () -> Unit,
) {
    var notify by remember { mutableStateOf(NotificationSettings()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { repository.getSettings() }
            .onSuccess { notify = it }
            .onFailure { error = repository.errorMessage(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Account", style = MaterialTheme.typography.titleLarge)
            Text(profile.siteName.ifBlank { "Fanfic Archive" }, style = MaterialTheme.typography.bodyLarge)
            Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.username.isNotBlank()) {
                Text("Signed in as ${profile.username}", style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                "Reading settings (text size and page colour) are on each chapter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Notifications", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Email notifications")
                Switch(
                    checked = notify.notifyEmail,
                    onCheckedChange = { notify = notify.copy(notifyEmail = it) },
                )
            }
            FanficTextField(
                value = notify.notifyEmailAddress.orEmpty(),
                onValueChange = { notify = notify.copy(notifyEmailAddress = it) },
                label = "Notification email",
                modifier = Modifier.fillMaxWidth(),
            )
            FanficTextField(
                value = notify.discordWebhookUrl.orEmpty(),
                onValueChange = { notify = notify.copy(discordWebhookUrl = it) },
                label = "Discord webhook URL",
                modifier = Modifier.fillMaxWidth(),
            )
            FanficTextField(
                value = notify.appriseUrls.orEmpty(),
                onValueChange = { notify = notify.copy(appriseUrls = it) },
                label = "Apprise URLs",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                onClick = {
                    scope.launch {
                        runCatching { repository.saveSettings(notify) }
                            .onSuccess {
                                notify = it
                                message = "Notification settings saved"
                                error = null
                            }
                            .onFailure { error = repository.errorMessage(it) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonLabel("Save notifications") }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
