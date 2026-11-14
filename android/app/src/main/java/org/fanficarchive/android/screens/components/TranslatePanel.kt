package org.fanficarchive.android.screens.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.TranslateLanguage
import org.fanficarchive.android.data.TranslationStatus
import java.util.Locale

data class TranslatedText(
    val title: String?,
    val contentHtml: String?,
    val sourceName: String?,
    val targetName: String?,
    val targetCode: String,
)

/**
 * Site-style Translate control: collapsible language picker + Translate / Show original.
 * When [enabled] is false (server has no LibreTranslate), renders nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatePanel(
    repository: ArchiveRepository,
    enabled: Boolean,
    sourceLanguage: String?,
    preferredCode: String,
    onPreferredChange: (String?) -> Unit,
    onTranslated: (TranslatedText?) -> Unit,
    /** Request translation for the current page; returns status (may be running/done/failed). */
    request: suspend (to: String) -> TranslationStatus,
    /** Poll while running (chapters). For work title/summary, return the same status. */
    poll: suspend (to: String) -> TranslationStatus = request,
    /** Optional prefetch after a chapter is done. */
    onDone: suspend (to: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    var languages by remember { mutableStateOf<List<TranslateLanguage>>(emptyList()) }
    var selected by remember { mutableStateOf(preferredCode) }
    var menuExpanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeCode by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pollJob by remember { mutableStateOf<Job?>(null) }

    fun clearTranslation() {
        pollJob?.cancel()
        busy = false
        progress = null
        error = null
        activeCode = null
        statusMessage = null
        onPreferredChange(null)
        onTranslated(null)
    }

    fun applyDone(status: TranslationStatus, code: String) {
        busy = false
        progress = null
        error = null
        activeCode = code
        onPreferredChange(code)
        onTranslated(
            TranslatedText(
                title = status.title,
                contentHtml = status.contentHtml,
                sourceName = status.sourceName,
                targetName = status.targetName,
                targetCode = code,
            ),
        )
        statusMessage = "Machine-translated from ${status.sourceName ?: "the original"} into ${status.targetName ?: code}."
    }

    fun startTranslation(code: String) {
        if (code.isBlank()) return
        pollJob?.cancel()
        busy = true
        error = null
        progress = 0
        statusMessage = "Translating into ${languages.find { it.code == code }?.name ?: code}…"
        selected = code
        pollJob = scope.launch {
            runCatching {
                var status = request(code)
                while (status.status == "running") {
                    progress = status.progress
                    statusMessage = "Translating into ${status.targetName ?: code}… ${status.progress ?: 0}%"
                    kotlinx.coroutines.delay(1_200)
                    status = poll(code)
                }
                when (status.status) {
                    "done" -> {
                        applyDone(status, code)
                        onDone(code)
                    }
                    "failed" -> {
                        busy = false
                        progress = null
                        error = status.error ?: "Translation failed."
                        statusMessage = null
                    }
                    else -> {
                        busy = false
                        progress = null
                        error = "Translation is unavailable."
                        statusMessage = null
                    }
                }
            }.onFailure {
                busy = false
                progress = null
                statusMessage = null
                error = repository.errorMessage(it)
            }
        }
    }

    LaunchedEffect(sourceLanguage) {
        runCatching { repository.translateLanguages(sourceLanguage) }
            .onSuccess { response ->
                languages = response.languages
                val browser = Locale.getDefault().language.lowercase()
                val pick = when {
                    preferredCode.isNotBlank() && languages.any { it.code == preferredCode } -> preferredCode
                    languages.any { it.code.equals(browser, true) || it.code.startsWith("$browser-") } ->
                        languages.first { it.code.equals(browser, true) || it.code.startsWith("$browser-") }.code
                    else -> languages.firstOrNull()?.code.orEmpty()
                }
                if (selected.isBlank() || languages.none { it.code == selected }) {
                    selected = pick
                }
            }
            .onFailure {
                languages = emptyList()
                error = repository.errorMessage(it)
            }
    }

    LaunchedEffect(preferredCode, languages) {
        if (preferredCode.isNotBlank() &&
            languages.any { it.code == preferredCode } &&
            activeCode != preferredCode &&
            !busy
        ) {
            startTranslation(preferredCode)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = !open },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Translate", style = MaterialTheme.typography.titleMedium)
                        if (!open && activeCode != null) {
                            Text(
                                languages.find { it.code == activeCode }?.name ?: activeCode!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (open) "Hide translate options" else "Show translate options",
                    )
                }

                if (open) {
                    if (languages.isNotEmpty()) {
                        ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                            FanficTextField(
                                value = languages.find { it.code == selected }?.name ?: selected,
                                onValueChange = {},
                                readOnly = true,
                                label = "Translate into",
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
                                textFieldModifier = Modifier.menuAnchor(),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                            )
                            ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                languages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang.name) },
                                        onClick = {
                                            selected = lang.code
                                            menuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                onClick = { startTranslation(selected) },
                                enabled = selected.isNotBlank() && !busy,
                            ) { ButtonLabel("Translate") }
                            if (activeCode != null) {
                                SecondaryButton(onClick = { clearTranslation() }) {
                                    ButtonLabel("Show original")
                                }
                            }
                        }
                    } else if (error == null) {
                        Text(
                            "Loading languages…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Machine translation by LibreTranslate on this archive’s own server. Chosen language sticks until you show the original.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Status stays visible even when the panel is collapsed (like the site's status line).
        progress?.let {
            LinearProgressIndicator(
                progress = { (it.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (activeCode != null && !busy && !open) {
                QuietButton(onClick = { clearTranslation() }) { ButtonLabel("Show original") }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (selected.isNotBlank()) {
                QuietButton(onClick = { startTranslation(selected); open = true }) { ButtonLabel("Try again") }
            }
        }
    }
}
