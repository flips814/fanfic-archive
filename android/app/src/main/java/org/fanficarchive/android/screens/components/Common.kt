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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun SiteBadge(site: String?, modifier: Modifier = Modifier) {
    Text(
        text = siteLabel(site),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(siteBadgeColor(site))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusPill(status: String?, modifier: Modifier = Modifier) {
    if (status.isNullOrBlank()) return
    val complete = isCompleteStatus(status)
    val color = if (complete) PillComplete else PillOngoing
    Text(
        text = if (complete) "Complete" else "In progress",
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun FollowingPill(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Text(
        text = "Following",
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
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
    Text(
        text = status.replaceFirstChar { it.uppercase() },
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkCard(work: WorkSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FanficCard(modifier = modifier, onClick = onClick) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SiteBadge(work.site)
            StatusPill(work.status)
            if (work.isFollowing) FollowingPill()
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
            Text(
                work.fandoms.take(3).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

@Composable
fun TagGroup(label: String, values: List<String>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(values.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPane(
    html: String,
    baseUrl: String,
    fontScale: Float = 1.1f,
    theme: String = "sepia",
    modifier: Modifier = Modifier,
) {
    val (bg, fg, link) = when (theme) {
        "dark" -> Triple("#18181b", "#e7e3da", "#c4b5fd")
        "light" -> Triple("#ffffff", "#1f1f24", "#5b21b6")
        "high" -> Triple("#000000", "#ffffff", "#c4b5fd")
        "site" -> Triple("#f8f7fc", "#1f1f24", "#7540bf")
        else -> Triple("#f5ecd7", "#3b2f1e", "#5b21b6")
    }
    val page = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <style>
          body { margin: 0; padding: 16px; background: $bg; color: $fg;
                 font-family: Georgia, "Times New Roman", serif; font-size: ${16 * fontScale}px; line-height: 1.75; }
          a { color: $link; }
          img { max-width: 100%; height: auto; }
          p { margin: 0 0 1em; }
        </style></head><body>$html</body></html>
    """.trimIndent()

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.parseColor(bg))
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(baseUrl, page, "text/html", "UTF-8", null)
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
