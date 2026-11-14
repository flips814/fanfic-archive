package org.fanficarchive.android.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ServerInfo

@Composable
fun LoginScreen(
    repository: ArchiveRepository,
    siteName: String,
    serverInfo: ServerInfo?,
    onLoggedIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(serverInfo) }
    val scope = rememberCoroutineScope()
    val localAvailable = info?.auth?.local != false

    LaunchedEffect(serverInfo) {
        info = serverInfo ?: runCatching { repository.refreshInfo() }.getOrNull()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(siteName.ifBlank { "Fanfic Archive" }, style = MaterialTheme.typography.headlineMedium)
            Text("Sign in with a local username and password.", style = MaterialTheme.typography.bodyLarge)

            if (info?.auth?.local == false) {
                Text(
                    "This server has no local accounts. Create one in the web Admin panel, or use the website for SSO.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("Username") },
                singleLine = true,
                enabled = localAvailable,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = localAvailable,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { repository.login(username, password) }
                            .onSuccess { onLoggedIn() }
                            .onFailure { error = repository.errorMessage(it) }
                        loading = false
                    }
                },
                enabled = localAvailable && username.isNotBlank() && password.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (loading) "Signing in…" else "Sign in")
            }
            OutlinedButton(onClick = onChangeServer, modifier = Modifier.fillMaxWidth()) {
                Text("Change server")
            }
        }
    }
}
