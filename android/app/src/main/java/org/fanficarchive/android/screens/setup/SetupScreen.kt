package org.fanficarchive.android.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.fanficarchive.android.data.ServerInfo
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.theme.ResponsivePane
import org.fanficarchive.android.screens.theme.rememberContentLayout

@Composable
fun SetupScreen(
    repository: ArchiveRepository,
    initialUrl: String = "",
    initialAllowHttp: Boolean = false,
    onConnected: (ServerInfo) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var allowHttp by remember { mutableStateOf(initialAllowHttp) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val layout = rememberContentLayout()

    Scaffold { padding ->
        ResponsivePane(layout = layout, modifier = Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(layout.contentPadding.coerceAtLeast(24.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Fanfic Archive", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Connect to your self-hosted archive. Enter the same public URL you use in a browser.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FanficTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = "Server URL",
                placeholder = "https://fics.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it })
                Text("Allow HTTP (LAN / local Docker)", style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryButton(
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { repository.probeServer(url, allowHttp) }
                            .onSuccess(onConnected)
                            .onFailure { error = repository.errorMessage(it) }
                        loading = false
                    }
                },
                enabled = url.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonLabel(if (loading) "Connecting…" else "Connect")
            }
        }
        }
    }
}
