package org.fanficarchive.android.screens.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.fanficarchive.android.data.WorkSummary
import org.fanficarchive.android.screens.theme.JobDone
import org.fanficarchive.android.screens.theme.JobFailed
import org.fanficarchive.android.screens.theme.JobRunning
import org.fanficarchive.android.screens.theme.PillComplete
import org.fanficarchive.android.screens.theme.PillOngoing
import org.fanficarchive.android.screens.theme.isCompleteStatus
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
        Text(title, style = MaterialTheme.typography.headlineSmall)
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

private val MetaShape = RoundedCornerShape(999.dp)

@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    background: Color = Color.Transparent,
    border: Color? = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
) {
    Text(
        text = text,
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(MetaShape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, MetaShape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SiteBadge(site: String?, modifier: Modifier = Modifier) {
    MetaChip(
        text = siteLabel(site),
        modifier = modifier,
        foreground = Color.White,
        background = siteBadgeColor(site),
        border = null,
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
        border = color,
    )
}

@Composable
fun FollowingPill(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    MetaChip(
        text = "Following",
        modifier = modifier,
        foreground = color,
        background = color.copy(alpha = 0.1f),
        border = color,
    )
}

@Composable
fun JobStatusPill(status: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        "done" -> JobDone to Color.White
        "failed" -> JobFailed to Color.White
        "running" -> JobRunning to Color(0xFF1A1A1A)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    MetaChip(
        text = status.replaceFirstChar { it.uppercase() },
        modifier = modifier,
        foreground = fg,
        background = bg,
        border = null,
    )
}

/** Soft violet tag chip matching the website `.tags .tag`. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    MetaChip(
        text = text,
        modifier = modifier,
        foreground = MaterialTheme.colorScheme.onSurface,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = null,
    )
}

/** Selectable filter / choice chip used in Library and Activity. */
@Composable
fun FilterTag(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val border = if (selected) null else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Text(
        text = label,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(MetaShape)
            .background(bg)
            .then(if (border != null) Modifier.border(1.5.dp, border, MetaShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkCard(
    work: WorkSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resumeChapter: Int? = null,
) {
    FanficCard(modifier = modifier, onClick = onClick) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SiteBadge(work.site)
            StatusPill(work.status)
            if (work.isFollowing) FollowingPill()
            if (resumeChapter != null) {
                MetaChip(
                    text = "Continue · Ch $resumeChapter",
                    foreground = MaterialTheme.colorScheme.primary,
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            work.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "by ${work.author ?: "unknown author"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (work.fandoms.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                work.fandoms.take(3).forEach { TagChip(it) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell("Chapters", work.chapters.toString())
            StatCell("Words", formatNumber(work.wordCount))
            StatCell("Updated", relativeTime(work.lastNewChapterAt ?: work.createdAt))
        }
        Text(
            buildString {
                append("Imported by ${work.addedBy?.name ?: "a removed user"}")
                if (!work.lastError.isNullOrBlank()) append(" · Update check failed")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (work.lastError.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    var contentHeightPx by remember(html, fontScale, theme, blendWithApp, scheme.background, scheme.onBackground) {
        mutableIntStateOf(0)
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
            if (wrapContentHeight) {
                // Let the Compose parent own vertical scrolling.
                webView.setOnTouchListener { _, _ -> false }
            } else {
                webView.setOnTouchListener(null)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!wrapContentHeight || view == null) return
                    view.post {
                        fun reportHeight() {
                            val fromContent = (view.contentHeight * view.scale).toInt()
                            if (fromContent > 0 && fromContent != contentHeightPx) {
                                contentHeightPx = fromContent
                            }
                        }
                        reportHeight()
                        view.postDelayed({ reportHeight() }, 150)
                        view.postDelayed({ reportHeight() }, 450)
                        view.evaluateJavascript(
                            "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);})()",
                        ) { value ->
                            val css = value?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
                            val px = (css * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                            if (px != contentHeightPx) contentHeightPx = px
                        }
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
