package org.fanficarchive.android.screens.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ChapterContent
import org.fanficarchive.android.data.ReaderPrefs
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.HtmlPane
import org.fanficarchive.android.screens.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    repository: ArchiveRepository,
    workId: Int,
    chapterNumber: Int,
    baseUrl: String,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
) {
    var chapter by remember { mutableStateOf<ChapterContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val readerPrefs by repository.readerPrefs.collectAsState(initial = ReaderPrefs())

    LaunchedEffect(workId, chapterNumber) {
        loading = true
        error = null
        runCatching { repository.getChapter(workId, chapterNumber) }
            .onSuccess { chapter = it }
            .onFailure { error = repository.errorMessage(it) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chapter?.workTitle ?: "Reader", style = MaterialTheme.typography.titleMedium)
                        chapter?.let {
                            Text("${it.number}. ${it.title}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            chapter?.let { ch ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(
                        onClick = { ch.prevNumber?.let(onOpenChapter) },
                        enabled = ch.prevNumber != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                        Text("Previous")
                    }
                    OutlinedButton(
                        onClick = { ch.nextNumber?.let(onOpenChapter) },
                        enabled = ch.nextNumber != null,
                    ) {
                        Text("Next")
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                    }
                }
            }
        },
    ) { padding ->
        when {
            loading -> LoadingBox(Modifier.padding(padding))
            error != null -> ErrorText(error!!, Modifier.padding(padding))
            chapter != null -> {
                HtmlPane(
                    html = chapter!!.contentHtml,
                    baseUrl = baseUrl,
                    fontScale = readerPrefs.fontScale,
                    theme = readerPrefs.theme,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}
