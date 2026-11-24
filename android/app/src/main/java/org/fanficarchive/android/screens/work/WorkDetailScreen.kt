package org.fanficarchive.android.screens.work

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.fanficarchive.android.data.ArchiveRepository
import org.fanficarchive.android.data.CommentItem
import org.fanficarchive.android.data.ReaderPrefs
import org.fanficarchive.android.data.ReadingProgress
import org.fanficarchive.android.data.VISIBILITY_PRIVATE
import org.fanficarchive.android.data.VISIBILITY_PUBLIC
import org.fanficarchive.android.data.WorkDetail
import org.fanficarchive.android.screens.components.ButtonLabel
import org.fanficarchive.android.screens.components.CoverImage
import org.fanficarchive.android.screens.components.DownloadedPill
import org.fanficarchive.android.screens.components.ErrorText
import org.fanficarchive.android.screens.components.absoluteMediaUrl
import org.fanficarchive.android.screens.components.FanficTextField
import org.fanficarchive.android.screens.components.FollowingPill
import org.fanficarchive.android.screens.components.HtmlPane
import org.fanficarchive.android.screens.components.LoadingBox
import org.fanficarchive.android.screens.components.MetaChip
import org.fanficarchive.android.screens.components.PrimaryButton
import org.fanficarchive.android.screens.components.PrivatePill
import org.fanficarchive.android.screens.components.PublicPill
import org.fanficarchive.android.screens.components.QuietButton
import org.fanficarchive.android.screens.components.SecondaryButton
import org.fanficarchive.android.screens.components.SiteBadge
import org.fanficarchive.android.screens.components.StatusPill
import org.fanficarchive.android.screens.components.TagGroup
import org.fanficarchive.android.screens.components.ToggleActionButton
import org.fanficarchive.android.screens.components.TranslatePanel
import org.fanficarchive.android.screens.components.TranslatedText
import org.fanficarchive.android.screens.components.formatNumber
import org.fanficarchive.android.screens.components.relativeTime
import org.fanficarchive.android.screens.theme.ResponsivePane
import org.fanficarchive.android.screens.theme.rememberContentLayout
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkDetailScreen(
    repository: ArchiveRepository,
    workId: Int,
    baseUrl: String,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onDeleted: () -> Unit,
) {
    var work by remember { mutableStateOf<WorkDetail?>(null) }
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentDraft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var translationEnabled by remember { mutableStateOf(false) }
    var translated by remember { mutableStateOf<TranslatedText?>(null) }
    var downloaded by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<String?>(null) }
    val readerPrefs by repository.readerPrefs.collectAsState(initial = ReaderPrefs())
    val progressMap by repository.readingProgressForCurrentServer()
        .collectAsState(initial = emptyMap())
    val bookmark: ReadingProgress? = progressMap[workId]
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val layout = rememberContentLayout()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        statusMessage = null
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            downloaded = repository.isWorkDownloaded(workId)
            runCatching { repository.syncReadingProgress() }
            runCatching {
                val detail = repository.getWork(workId)
                val thread = runCatching { repository.listComments(workId) }.getOrNull()
                detail to thread
            }.onSuccess { (detail, thread) ->
                work = detail
                comments = thread?.comments.orEmpty()
                if (detail.fromOffline) downloaded = true
            }.onFailure { error = repository.errorMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(workId) {
        translated = null
        load()
    }

    LaunchedEffect(Unit) {
        runCatching { repository.refreshInfo() }
            .onSuccess { translationEnabled = it.features.translation }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(work?.title ?: "Work", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            loading && work == null -> LoadingBox(Modifier.padding(padding))
            error != null && work == null -> ErrorText(error!!, Modifier.padding(padding))
            work != null -> {
                val detail = work!!
                val displayTitle = translated?.title?.takeIf { it.isNotBlank() } ?: detail.title
                val displaySummary = translated?.contentHtml?.takeIf { it.isNotBlank() } ?: detail.summaryHtml
                ResponsivePane(layout = layout, modifier = Modifier.padding(padding)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(layout.contentPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    val cover = absoluteMediaUrl(baseUrl, detail.coverUrl)
                    @Composable
                    fun HeroMeta() {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SiteBadge(detail.site)
                            StatusPill(detail.status)
                            if (detail.isPrivate) PrivatePill() else PublicPill()
                            if (downloaded) DownloadedPill()
                            if (detail.isFollowing) FollowingPill()
                            if (detail.rating != null) {
                                MetaChip(text = detail.rating)
                            }
                            MetaChip(text = "${detail.commentCount} comments")
                        }
                        if (detail.fromOffline) {
                            Text(
                                "Showing your offline copy — connect to the server to refresh.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(displayTitle, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "by ${detail.author ?: "unknown author"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            listOfNotNull(
                                "${detail.chapters} chapters",
                                "${formatNumber(detail.wordCount)} words",
                                detail.addedBy?.let { "imported by ${it.name}" },
                                "updated ${relativeTime(detail.lastNewChapterAt ?: detail.createdAt)}",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!layout.isCompact && cover != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CoverImage(
                                url = cover,
                                contentDescription = "Cover for $displayTitle",
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .fillMaxWidth(0.32f),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HeroMeta()
                            }
                        }
                    } else {
                        if (cover != null) {
                            CoverImage(
                                url = cover,
                                contentDescription = "Cover for $displayTitle",
                                modifier = Modifier
                                    .fillMaxWidth(if (layout.isCompact) 0.45f else 0.32f)
                                    .padding(bottom = 4.dp),
                            )
                        }
                        HeroMeta()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val firstChapter = detail.chapterList.firstOrNull()?.number
                        val resumeChapter = (bookmark?.chapterNumber ?: detail.resumeChapter)
                            ?.takeIf { number -> detail.chapterList.any { it.number == number } }
                        PrimaryButton(
                            onClick = { (resumeChapter ?: firstChapter)?.let(onOpenChapter) },
                            enabled = (resumeChapter ?: firstChapter) != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            ButtonLabel(
                                if (resumeChapter != null) "Continue · Ch $resumeChapter" else "Start reading",
                            )
                        }
                        SecondaryButton(
                            onClick = {
                                scope.launch {
                                    runCatching { repository.downloadEpub(workId) }
                                        .onSuccess { (name, bytes) ->
                                            val file = File(context.cacheDir, name)
                                            file.writeBytes(bytes)
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.files",
                                                file,
                                            )
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/epub+zip"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share EPUB"))
                                        }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            filled = true,
                        ) { ButtonLabel("EPUB") }
                    }
                    if (bookmark != null) {
                        QuietButton(
                            onClick = {
                                scope.launch { repository.clearReadingProgress(workId) }
                            },
                        ) { ButtonLabel("Clear bookmark") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleActionButton(
                            pressed = detail.isFollowing,
                            onClick = {
                                scope.launch {
                                    runCatching { repository.setFollowing(workId, !detail.isFollowing) }
                                        .onSuccess { work = detail.copy(isFollowing = it.isFollowing) }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                        ) { ButtonLabel(if (detail.isFollowing) "Following" else "Follow") }
                        SecondaryButton(
                            onClick = {
                                scope.launch {
                                    runCatching { repository.checkUpdates(workId) }
                                        .onSuccess { statusMessage = "Update check queued" }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                        ) { ButtonLabel("Check updates") }
                        if (downloaded) {
                            SecondaryButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { repository.removeOfflineWork(workId) }
                                            .onSuccess {
                                                downloaded = false
                                                statusMessage = "Offline copy removed"
                                            }
                                            .onFailure { error = repository.errorMessage(it) }
                                    }
                                },
                                enabled = !downloading,
                            ) { ButtonLabel("Remove download") }
                        } else {
                            SecondaryButton(
                                onClick = {
                                    scope.launch {
                                        downloading = true
                                        downloadProgress = "Starting…"
                                        error = null
                                        runCatching {
                                            repository.downloadWorkOffline(workId) { done, total ->
                                                downloadProgress = "Downloading $done / $total"
                                            }
                                        }.onSuccess {
                                            downloaded = true
                                            downloadProgress = null
                                            statusMessage = "Saved for offline reading"
                                        }.onFailure {
                                            downloaded = repository.isWorkDownloaded(workId)
                                            downloadProgress = null
                                            error = repository.errorMessage(it)
                                        }
                                        downloading = false
                                    }
                                },
                                enabled = !downloading,
                            ) { ButtonLabel(if (downloading) "Downloading…" else "Download for offline") }
                        }
                        if (detail.canManage) {
                            SecondaryButton(
                                onClick = {
                                    val next = if (detail.isPrivate) VISIBILITY_PUBLIC else VISIBILITY_PRIVATE
                                    scope.launch {
                                        runCatching { repository.setVisibility(workId, next) }
                                            .onSuccess {
                                                work = detail.copy(visibility = it.visibility)
                                                statusMessage = if (it.visibility == VISIBILITY_PRIVATE) {
                                                    "Now private — only you can see it"
                                                } else {
                                                    "Now public — everyone on the server can see it"
                                                }
                                            }
                                            .onFailure { error = repository.errorMessage(it) }
                                    }
                                },
                            ) { ButtonLabel(if (detail.isPrivate) "Make public" else "Make private") }
                            SecondaryButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { repository.toggleAutoUpdate(workId) }
                                            .onSuccess { work = detail.copy(autoUpdate = it.autoUpdate) }
                                            .onFailure { error = repository.errorMessage(it) }
                                    }
                                },
                            ) { ButtonLabel(if (detail.autoUpdate) "Auto-update on" else "Auto-update off") }
                        }
                    }
                    if (detail.canManage) {
                        Text(
                            if (detail.isPrivate) {
                                "Private — only you can find and read this work."
                            } else {
                                "Public — everyone on this server can find and read this work."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TranslatePanel(
                        repository = repository,
                        enabled = translationEnabled,
                        sourceLanguage = detail.languageCode,
                        preferredCode = readerPrefs.translateTo,
                        onPreferredChange = { code ->
                            scope.launch { repository.saveTranslateTo(code) }
                        },
                        onTranslated = { translated = it },
                        request = { to -> repository.requestWorkTranslation(workId, to) },
                        poll = { to -> repository.getWorkTranslation(workId, to) },
                    )
                    downloadProgress?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    TagGroup("Fandoms", detail.fandoms)
                    TagGroup("Warnings", detail.warnings)
                    TagGroup("Relationships", detail.relationships)
                    TagGroup("Characters", detail.characters)
                    TagGroup("Tags", detail.tags)
                    if (!displaySummary.isNullOrBlank()) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium)
                        HtmlPane(
                            html = displaySummary,
                            baseUrl = baseUrl,
                            fontScale = 1f,
                            theme = "app",
                            blendWithApp = true,
                            wrapContentHeight = true,
                        )
                    }
                    Text("Chapters", style = MaterialTheme.typography.titleLarge)
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
                                    formatNumber(chapter.wordCount) + " words",
                                    chapter.importedBy?.name?.let { "by $it" },
                                    if (chapter.commentCount > 0) "${chapter.commentCount} comments" else null,
                                    if (chapter.removedFromSource) "removed from source" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text("Comments (${comments.sumOf { 1 + it.replies.size }})", style = MaterialTheme.typography.titleLarge)
                    comments.forEach { comment ->
                        CommentBlock(comment)
                        comment.replies.forEach { reply ->
                            CommentBlock(reply, indented = true)
                        }
                    }
                    FanficTextField(
                        value = commentDraft,
                        onValueChange = { commentDraft = it },
                        label = "Write a comment",
                        singleLine = false,
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        onClick = {
                            scope.launch {
                                runCatching { repository.postComment(workId, commentDraft) }
                                    .onSuccess {
                                        commentDraft = ""
                                        load()
                                    }
                                    .onFailure { error = repository.errorMessage(it) }
                            }
                        },
                        enabled = commentDraft.isNotBlank(),
                    ) { ButtonLabel("Post comment") }
                    if (detail.canManage) {
                        SecondaryButton(
                            onClick = {
                                scope.launch {
                                    runCatching { repository.deleteWork(workId) }
                                        .onSuccess { onDeleted() }
                                        .onFailure { error = repository.errorMessage(it) }
                                }
                            },
                        ) { ButtonLabel("Delete work") }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentBlock(comment: CommentItem, indented: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 16.dp else 0.dp, bottom = 8.dp),
    ) {
        Text(
            if (comment.deleted) "Deleted comment" else (comment.user?.name ?: "User"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (comment.deleted) {
                "This comment was removed."
            } else if (comment.spoiler) {
                "Spoiler: ${comment.body}"
            } else {
                comment.body.orEmpty()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
