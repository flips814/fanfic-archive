package org.fanficarchive.android.screens.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        "dark" -> Triple("#121A16", "#E8EFEA", "#8FBF9F")
        "light" -> Triple("#FFFFFF", "#1A1A1A", "#1B3A2F")
        "high" -> Triple("#000000", "#FFFFFF", "#FFFF00")
        else -> Triple("#F4EFE6", "#1A1A1A", "#1B3A2F")
    }
    val page = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <style>
          body { margin: 0; padding: 16px; background: $bg; color: $fg;
                 font-family: Georgia, serif; font-size: ${16 * fontScale}px; line-height: 1.65; }
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
