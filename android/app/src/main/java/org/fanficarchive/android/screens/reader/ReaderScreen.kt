package org.fanficarchive.android.screens.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.ChapterContent
import org.fanficarchive.android.data.ChapterSummary
import org.fanficarchive.android.data.ReaderPrefs
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.HtmlPane
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.QuietButton
import org.fanficarchive.android.screens.components.ReadingSettingsPanel
import org.fanficarchive.android.screens.components.SecondaryButton
import org.fanficarchive.android.screens.components.TranslatePanel
import org.fanficarchive.android.screens.components.TranslatedText
import org.fanficarchive.android.screens.components.formatNumber
import org.fanficarchive.android.screens.theme.rememberContentLayout
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
    var chromeVisible by remember { mutableStateOf(false) }
    var restoreOffset by remember { mutableIntStateOf(0) }
    var restoreFraction by remember { mutableStateOf(0f) }
    var needsRestore by remember { mutableStateOf(false) }
    var restoreDone by remember { mutableStateOf(false) }
    var htmlHeightPx by remember { mutableIntStateOf(0) }
    val readerPrefs by repository.readerPrefs.collectAsState(initial = ReaderPrefs())
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layout = rememberContentLayout()
    val readerMaxWidth = if (layout.isCompact) null else 720.dp

    fun toggleChrome() {
        chromeVisible = !chromeVisible
    }

    suspend fun persistProgress(offset: Int = scroll.value, max: Int = scroll.maxValue) {
        val fraction = if (max > 0) (offset.toFloat() / max).coerceIn(0f, 1f) else restoreFraction
        repository.saveReadingProgress(workId, chapterNumber, offset.coerceAtLeast(0), fraction)
    }

    LaunchedEffect(workId, chapterNumber) {
        loading = true
        error = null
        translated = null
        restoreDone = false
        needsRestore = false
        htmlHeightPx = 0
        scroll.scrollTo(0)
        val bookmark = repository.getReadingProgress(workId)
        if (bookmark != null && bookmark.chapterNumber == chapterNumber) {
            restoreOffset = bookmark.scrollOffset
            restoreFraction = bookmark.scrollFraction
            needsRestore = bookmark.scrollFraction > 0.001f || bookmark.scrollOffset > 0
        } else {
            restoreOffset = 0
            restoreFraction = 0f
            needsRestore = false
        }
        runCatching { repository.getChapter(workId, chapterNumber) }
            .onSuccess { chapter = it }
            .onFailure { error = repository.errorMessage(it) }
        // Keep chapter bookmark, but do not clobber a saved in-chapter position with 0.
        if (bookmark == null || bookmark.chapterNumber != chapterNumber) {
            repository.saveReadingProgress(workId, chapterNumber, 0, 0f)
        }
        loading = false
        if (!needsRestore) restoreDone = true
    }

    LaunchedEffect(workId) {
        runCatching { repository.getWork(workId) }
            .onSuccess { chapterList = it.chapterList }
    }

    LaunchedEffect(Unit) {
        runCatching { repository.refreshInfo() }
            .onSuccess { translationEnabled = it.features.translation }
    }

    // Restore after the chapter WebView has a stable laid-out height (not the 120dp placeholder).
    LaunchedEffect(loading, chapter, needsRestore, htmlHeightPx) {
        if (loading || chapter == null || restoreDone || !needsRestore) return@LaunchedEffect
        if (htmlHeightPx < 200) return@LaunchedEffect

        // Wait until scroll range stops growing so we don't restore against a short page.
        var lastMax = -1
        var stableTicks = 0
        while (stableTicks < 4) {
            val max = scroll.maxValue
            if (max > 0 && max == lastMax) stableTicks++ else stableTicks = 0
            lastMax = max
            kotlinx.coroutines.delay(150)
        }
        if (lastMax <= 0) return@LaunchedEffect

        val target = when {
            restoreFraction > 0.001f -> (restoreFraction * lastMax).toInt()
            else -> restoreOffset
        }.coerceIn(0, lastMax)

        scroll.scrollTo(target)
        // One more pass after late WebView growth.
        kotlinx.coroutines.delay(250)
        val finalMax = scroll.maxValue
        val finalTarget = when {
            restoreFraction > 0.001f -> (restoreFraction * finalMax).toInt()
            else -> restoreOffset
        }.coerceIn(0, finalMax)
        scroll.scrollTo(finalTarget)
        restoreDone = true
        needsRestore = false
    }

    // Persist scroll position while reading (never during restore — that was wiping bookmarks).
    LaunchedEffect(workId, chapterNumber) {
        try {
            snapshotFlow { scroll.value to scroll.maxValue }
                .debounce(400)
                .distinctUntilChanged()
                .collect { (offset, max) ->
                    if (!loading && chapter != null && restoreDone && max > 0) {
                        persistProgress(offset, max)
                    }
                }
        } finally {
            if (restoreDone && scroll.maxValue > 0) {
                persistProgress()
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

    // Overlay chrome instead of Scaffold topBar/bottomBar — AnimatedVisibility inside
    // those slots does not reliably collapse, so bars stayed on screen.
    val view = LocalView.current
    DisposableEffect(chromeVisible) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (readerMaxWidth != null) Modifier.widthIn(max = readerMaxWidth)
                            else Modifier,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { toggleChrome() })
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(displayTitle, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            listOfNotNull(
                                "${formatNumber(ch.wordCount)} words",
                                "about $minutes min",
                                if (ch.commentCount > 0) "${ch.commentCount} comments" else null,
                                if (ch.fromOffline) "Offline copy" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Panels keep their own taps; don't toggle chrome when using them.
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        onContentHeightPx = { htmlHeightPx = it },
                        onTap = { toggleChrome() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ch.nextNumber == null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Caught up",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    "You've reached the latest chapter. Check back when a new one lands, or open the work for details.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                QuietButton(onClick = onBack) { ButtonLabel("Back to work") }
                            }
                        }
                    }
                }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
                    TopAppBar(
                        modifier = Modifier.statusBarsPadding(),
                        title = {
                            Column {
                                Text(
                                    chapter?.workTitle ?: "Reader",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
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
                }
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
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && chapter != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            val ch = chapter
            if (ch != null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
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
                        if (ch.nextNumber != null) {
                            SecondaryButton(
                                onClick = { onOpenChapter(ch.nextNumber) },
                                modifier = Modifier.weight(1f),
                            ) {
                                ButtonLabel("Next")
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                            }
                        } else {
                            val complete = ch.workChapters > 0 && ch.number >= ch.workChapters
                            SecondaryButton(
                                onClick = onBack,
                                filled = true,
                                modifier = Modifier.weight(1f),
                            ) {
                                ButtonLabel(if (complete) "Finished" else "Caught up")
                            }
                        }
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
