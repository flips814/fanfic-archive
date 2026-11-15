package org.fanficarchive.android.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ChapterContent
import org.fanficarchive.android.data.ChapterSummary
import org.fanficarchive.android.data.ReaderPrefs
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.HtmlPane
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.ReadingSettingsPanel
import org.fanficarchive.android.screens.components.SecondaryButton
import org.fanficarchive.android.screens.components.TranslatePanel
import org.fanficarchive.android.screens.components.TranslatedText
import org.fanficarchive.android.screens.components.formatNumber
import kotlin.math.ceil
import kotlin.math.max

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
    var chapterList by remember { mutableStateOf<List<ChapterSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var translationEnabled by remember { mutableStateOf(false) }
    var translated by remember { mutableStateOf<TranslatedText?>(null) }
    var showContents by remember { mutableStateOf(false) }
    var restoreOffset by remember { mutableIntStateOf(-1) }
    var restoreDone by remember { mutableStateOf(false) }
    val readerPrefs by repository.readerPrefs.collectAsState(initial = ReaderPrefs())
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(workId, chapterNumber) {
        loading = true
        error = null
        translated = null
        restoreDone = false
        scroll.scrollTo(0)
        val bookmark = repository.getReadingProgress(workId)
        restoreOffset = if (bookmark?.chapterNumber == chapterNumber) bookmark.scrollOffset else 0
        runCatching { repository.getChapter(workId, chapterNumber) }
            .onSuccess { chapter = it }
            .onFailure { error = repository.errorMessage(it) }
        // Bookmark the chapter immediately so Continue works even before scrolling.
        repository.saveReadingProgress(workId, chapterNumber, restoreOffset.coerceAtLeast(0))
        loading = false
    }

    LaunchedEffect(workId) {
        runCatching { repository.getWork(workId) }
            .onSuccess { chapterList = it.chapterList }
    }

    LaunchedEffect(Unit) {
        runCatching { repository.refreshInfo() }
            .onSuccess { translationEnabled = it.features.translation }
    }

    // Restore scroll once chapter content has laid out enough height.
    LaunchedEffect(loading, chapter, restoreOffset) {
        if (loading || chapter == null || restoreDone || restoreOffset < 0) return@LaunchedEffect
        val target = restoreOffset
        if (target == 0) {
            restoreDone = true
            return@LaunchedEffect
        }
        snapshotFlow { scroll.maxValue }
            .filter { it > 0 }
            .first()
        // HtmlPane may grow after WebView measures; retry until we can reach the offset.
        for (i in 0 until 8) {
            val max = scroll.maxValue
            scroll.scrollTo(target.coerceIn(0, max))
            if (max >= target) break
            kotlinx.coroutines.delay(150)
        }
        scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
        restoreDone = true
    }

    // Persist scroll position while reading.
    LaunchedEffect(workId, chapterNumber) {
        snapshotFlow { scroll.value }
            .debounce(350)
            .distinctUntilChanged()
            .collect { offset ->
                if (!loading && chapter != null && restoreDone) {
                    repository.saveReadingProgress(workId, chapterNumber, offset)
                }
            }
    }

    val progress = chapter?.let {
        if (it.workChapters <= 0) 0f else it.number.toFloat() / it.workChapters.toFloat()
    } ?: 0f

    if (showContents) {
        ModalBottomSheet(
            onDismissRequest = { showContents = false },
            sheetState = sheetState,
        ) {
            ChapterContentsSheet(
                workTitle = chapter?.workTitle ?: "Chapters",
                chapters = chapterList,
                currentNumber = chapterNumber,
                onSelect = { number ->
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        showContents = false
                        if (number != chapterNumber) onOpenChapter(number)
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chapter?.workTitle ?: "Reader", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        chapter?.let {
                            Text(
                                "Chapter ${it.number} of ${it.workChapters}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to work")
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(
                        onClick = { ch.prevNumber?.let(onOpenChapter) },
                        enabled = ch.prevNumber != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                        ButtonLabel("Prev")
                    }
                    SecondaryButton(
                        onClick = { showContents = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel("Contents")
                    }
                    SecondaryButton(
                        onClick = { ch.nextNumber?.let(onOpenChapter) },
                        enabled = ch.nextNumber != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        ButtonLabel("Next")
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            when {
                loading -> LoadingBox()
                error != null -> ErrorText(error!!)
                chapter != null -> {
                    val ch = chapter!!
                    val minutes = max(1, ceil((ch.wordCount ?: 0) / 230.0).toInt())
                    val displayTitle = translated?.title?.takeIf { it.isNotBlank() } ?: ch.title
                    val displayHtml = translated?.contentHtml?.takeIf { it.isNotBlank() } ?: ch.contentHtml
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(displayTitle, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                listOfNotNull(
                                    "${formatNumber(ch.wordCount)} words",
                                    "about $minutes min",
                                    if (ch.commentCount > 0) "${ch.commentCount} comments" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TranslatePanel(
                                repository = repository,
                                enabled = translationEnabled,
                                sourceLanguage = ch.workLanguageCode,
                                preferredCode = readerPrefs.translateTo,
                                onPreferredChange = { code ->
                                    scope.launch { repository.saveTranslateTo(code) }
                                },
                                onTranslated = { translated = it },
                                request = { to -> repository.requestChapterTranslation(workId, chapterNumber, to) },
                                poll = { to -> repository.getChapterTranslation(workId, chapterNumber, to) },
                                onDone = { to ->
                                    ch.nextNumber?.let { next ->
                                        runCatching { repository.requestChapterTranslation(workId, next, to) }
                                    }
                                },
                            )
                            ReadingSettingsPanel(
                                prefs = readerPrefs,
                                onChange = { scale, theme ->
                                    scope.launch { repository.saveReaderPrefs(scale, theme) }
                                },
                            )
                        }
                        HtmlPane(
                            html = displayHtml,
                            baseUrl = baseUrl,
                            fontScale = readerPrefs.fontScale,
                            theme = readerPrefs.theme,
                            wrapContentHeight = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterContentsSheet(
    workTitle: String,
    chapters: List<ChapterSummary>,
    currentNumber: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(chapters, currentNumber) {
        val index = chapters.indexOfFirst { it.number == currentNumber }
        if (index >= 0) listState.scrollToItem(index)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            workTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (chapters.isEmpty()) {
            Text(
                "Loading chapters…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                items(chapters, key = { it.number }) { chapter ->
                    val current = chapter.number == currentNumber
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(chapter.number) }
                            .background(
                                if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.surface,
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "${chapter.number}. ${chapter.title}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            listOfNotNull(
                                formatNumber(chapter.wordCount) + " words",
                                if (chapter.commentCount > 0) "${chapter.commentCount} comments" else null,
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
