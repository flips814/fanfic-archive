package org.fanficarchive.android.screens.components

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import org.fanficarchive.android.data.WorkSummary
import org.fanficarchive.android.screens.theme.JobDone
import org.fanficarchive.android.screens.theme.JobFailed
import org.fanficarchive.android.screens.theme.JobRunning
import org.fanficarchive.android.screens.theme.PillComplete
import org.fanficarchive.android.screens.theme.PillOngoing
import org.fanficarchive.android.screens.theme.isCompleteStatus
import org.fanficarchive.android.screens.theme.rememberContentLayout
import org.fanficarchive.android.screens.theme.siteBadgeColor
import org.fanficarchive.android.screens.theme.siteLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FanficCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
    if (onClick != null) {
        ElevatedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            content = { body() },
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            content = { body() },
        )
    }
}

@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    border: Color? = null,
) {
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.heightIn(min = 32.dp),
        colors = SuggestionChipDefaults.suggestionChipColors(
            disabledContainerColor = background,
            disabledLabelColor = foreground,
        ),
        border = if (border != null) {
            SuggestionChipDefaults.suggestionChipBorder(
                enabled = false,
                disabledBorderColor = border,
            )
        } else {
            SuggestionChipDefaults.suggestionChipBorder(enabled = false, borderColor = Color.Transparent)
        },
    )
}

@Composable
fun SiteBadge(site: String?, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(siteLabel(site), style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = siteBadgeColor(site),
            disabledLabelColor = Color.White,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = false, borderColor = Color.Transparent),
    )
}

@Composable
fun StatusPill(status: String?, modifier: Modifier = Modifier) {
    if (status.isNullOrBlank()) return
    val complete = isCompleteStatus(status)
    val color = if (complete) PillComplete else PillOngoing
    MetaChip(
        text = if (complete) "Complete" else "In progress",
        modifier = modifier,
        foreground = color,
        background = color.copy(alpha = 0.12f),
        border = color,
    )
}

@Composable
fun FollowingPill(modifier: Modifier = Modifier) {
    MetaChip(
        text = "Following",
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        background = MaterialTheme.colorScheme.primaryContainer,
        border = null,
    )
}

@Composable
fun PrivatePill(modifier: Modifier = Modifier) {
    MetaChip(
        text = "Private",
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onSecondaryContainer,
        background = MaterialTheme.colorScheme.secondaryContainer,
        border = null,
    )
}

@Composable
fun PublicPill(modifier: Modifier = Modifier) {
    MetaChip(
        text = "Public",
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun DownloadedPill(modifier: Modifier = Modifier) {
    MetaChip(
        text = "Downloaded",
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onTertiaryContainer,
        background = MaterialTheme.colorScheme.tertiaryContainer,
        border = null,
    )
}

@Composable
fun JobStatusPill(status: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        "done" -> JobDone to Color.White
        "failed" -> JobFailed to Color.White
        "running" -> JobRunning to Color(0xFF1A1A1A)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    MetaChip(
        text = status.replaceFirstChar { it.uppercase() },
        modifier = modifier,
        foreground = fg,
        background = bg,
        border = null,
    )
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        colors = SuggestionChipDefaults.suggestionChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled = false, borderColor = Color.Transparent),
    )
}

@Composable
fun FilterTag(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.heightIn(min = 48.dp),
    )
}

fun absoluteMediaUrl(baseUrl: String, path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (
        path.startsWith("http://") ||
        path.startsWith("https://") ||
        path.startsWith("file:")
    ) {
        return path
    }
    return baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkCard(
    work: WorkSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resumeChapter: Int? = null,
    downloaded: Boolean = false,
    baseUrl: String = "",
) {
    val layout = rememberContentLayout()
    FanficCard(modifier = modifier, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val cover = absoluteMediaUrl(baseUrl, work.coverUrl)
            if (cover != null) {
                CoverImage(
                    url = cover,
                    contentDescription = "Cover for ${work.title}",
                    modifier = Modifier.width(if (layout.isCompact) 64.dp else 80.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    work.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "by ${work.author ?: "unknown author"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("${work.chapters} ch · ${formatNumber(work.wordCount)} words")
                        append(" · ${relativeTime(work.lastNewChapterAt ?: work.createdAt)}")
                        if (resumeChapter != null) append(" · Continue Ch $resumeChapter")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SiteBadge(work.site)
                    StatusPill(work.status)
                    if (!layout.isCompact) {
                        if (work.isPrivate) PrivatePill()
                        if (downloaded) DownloadedPill()
                        if (work.isFollowing) FollowingPill()
                        work.fandoms.take(2).forEach { TagChip(it) }
                    } else {
                        if (downloaded) DownloadedPill()
                        if (work.isFollowing) FollowingPill()
                    }
                }
                if (!work.lastError.isNullOrBlank()) {
                    Text(
                        "Update check failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagGroup(label: String, values: List<String>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { TagChip(it) }
        }
    }
}

private fun Color.toCssHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPane(
    html: String,
    baseUrl: String,
    fontScale: Float = 1.1f,
    theme: String = "sepia",
    /** When true, sizes to document height so a parent Column can scroll the whole page. */
    wrapContentHeight: Boolean = false,
    /** Tighter padding and UI sans font — for summaries that should match surrounding screens. */
    blendWithApp: Boolean = false,
    onContentHeightPx: ((Int) -> Unit)? = null,
    /** Fired on a quick tap (not a scroll drag). Used for immersive chrome toggle. */
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val latestOnTap by rememberUpdatedState(onTap)
    var contentHeightPx by remember(html, fontScale, theme, blendWithApp, scheme.background, scheme.onBackground) {
        mutableIntStateOf(0)
    }
    LaunchedEffect(contentHeightPx) {
        if (contentHeightPx > 0) onContentHeightPx?.invoke(contentHeightPx)
    }
    val (bg, fg, link) = when {
        blendWithApp || theme == "app" -> Triple(
            scheme.background.toCssHex(),
            scheme.onBackground.toCssHex(),
            scheme.primary.toCssHex(),
        )
        theme == "dark" -> Triple("#18181b", "#e7e3da", "#c4b5fd")
        theme == "light" -> Triple("#ffffff", "#1f1f24", "#5b21b6")
        theme == "high" -> Triple("#000000", "#ffffff", "#c4b5fd")
        theme == "site" -> Triple("#f8f7fc", "#1f1f24", "#7540bf")
        else -> Triple("#f5ecd7", "#3b2f1e", "#5b21b6")
    }
    val fontFamily = if (blendWithApp) {
        """-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif"""
    } else {
        """Georgia, "Times New Roman", serif"""
    }
    val bodyPadding = if (blendWithApp) "0" else "16px"
    val page = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <style>
          html, body { margin: 0; padding: 0; background: $bg; color: $fg; }
          body { padding: $bodyPadding; font-family: $fontFamily;
                 font-size: ${16 * fontScale}px; line-height: 1.6; }
          a { color: $link; }
          img { max-width: 100%; height: auto; }
          p { margin: 0 0 0.85em; }
          p:last-child { margin-bottom: 0; }
        </style></head><body>$html</body></html>
    """.trimIndent()

    val heightModifier = if (wrapContentHeight) {
        val h = contentHeightPx
        if (h > 0) Modifier.height(with(density) { h.toDp() }) else Modifier.height(120.dp)
    } else {
        Modifier
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .then(heightModifier),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = wrapContentHeight
                settings.builtInZoomControls = !wrapContentHeight
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = !wrapContentHeight
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                setBackgroundColor(android.graphics.Color.parseColor(bg))
            }
        },
        update = { webView ->
            webView.setBackgroundColor(android.graphics.Color.parseColor(bg))
            webView.settings.javaScriptEnabled = wrapContentHeight
            webView.isVerticalScrollBarEnabled = !wrapContentHeight
            // Detect taps without blocking parent scroll (wrap-content chapters) or WebView scroll.
            var downX = 0f
            var downY = 0f
            var downTime = 0L
            webView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downTime = event.eventTime
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = abs(event.x - downX) > 24f || abs(event.y - downY) > 24f
                        val quick = event.eventTime - downTime < 400
                        if (!moved && quick) {
                            latestOnTap?.invoke()
                        }
                    }
                }
                false
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!wrapContentHeight || view == null) return
                    view.post {
                        fun reportHeight() {
                            val density = view.resources.displayMetrics.density
                            val fromContent = (view.contentHeight * view.scale).toInt()
                            // Prefer the larger of contentHeight and JS scrollHeight so tall chapters
                            // finish laying out before reading-position restore runs.
                            view.evaluateJavascript(
                                "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);})()",
                            ) { value ->
                                val css = value?.trim('"')?.toFloatOrNull()?.toInt() ?: 0
                                val fromJs = if (css > 0) (css * density).toInt() else 0
                                val px = maxOf(fromContent, fromJs).coerceAtLeast(1)
                                if (px != contentHeightPx) contentHeightPx = px
                            }
                        }
                        reportHeight()
                        view.postDelayed({ reportHeight() }, 100)
                        view.postDelayed({ reportHeight() }, 300)
                        view.postDelayed({ reportHeight() }, 700)
                        view.postDelayed({ reportHeight() }, 1200)
                    }
                }
            }
            if (webView.tag != page) {
                webView.tag = page
                webView.loadDataWithBaseURL(baseUrl, page, "text/html", "UTF-8", null)
            }
        },
    )
}

fun formatNumber(value: Int?): String =
    value?.let { "%,d".format(it) } ?: "—"

fun relativeTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "never"
    return runCatching {
        val instant = Instant.parse(iso)
        val days = ChronoUnit.DAYS.between(instant, Instant.now())
        when {
            days < 1 -> {
                val hours = ChronoUnit.HOURS.between(instant, Instant.now())
                when {
                    hours < 1 -> "just now"
                    hours == 1L -> "1 hour ago"
                    else -> "$hours hours ago"
                }
            }
            days == 1L -> "1 day ago"
            days < 30 -> "$days days ago"
            else -> DateTimeFormatter.ofPattern("MMM d, yyyy")
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    }.getOrDefault(iso.take(10))
}
