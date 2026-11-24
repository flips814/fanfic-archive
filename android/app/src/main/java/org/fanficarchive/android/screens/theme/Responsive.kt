package org.fanficarchive.android.screens.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidthClass { Compact, Medium, Expanded }

data class ContentLayout(
    val widthClass: WidthClass,
    val screenWidth: Dp,
    val contentPadding: Dp,
    val maxContentWidth: Dp?,
    val libraryColumns: Int,
    val filterColumns: Int,
    val useNavRail: Boolean,
) {
    val isCompact: Boolean get() = widthClass == WidthClass.Compact
    val isExpanded: Boolean get() = widthClass == WidthClass.Expanded
}

@Composable
fun rememberContentLayout(): ContentLayout {
    val widthDp = LocalConfiguration.current.screenWidthDp.dp
    return remember(widthDp) {
        val widthClass = when {
            widthDp < 600.dp -> WidthClass.Compact
            widthDp < 840.dp -> WidthClass.Medium
            else -> WidthClass.Expanded
        }
        ContentLayout(
            widthClass = widthClass,
            screenWidth = widthDp,
            contentPadding = when (widthClass) {
                WidthClass.Compact -> 16.dp
                WidthClass.Medium -> 20.dp
                WidthClass.Expanded -> 24.dp
            },
            maxContentWidth = when (widthClass) {
                WidthClass.Expanded -> 1120.dp
                WidthClass.Medium -> 880.dp
                WidthClass.Compact -> null
            },
            libraryColumns = when {
                widthDp >= 1100.dp -> 3
                widthDp >= 600.dp -> 2
                else -> 1
            },
            filterColumns = if (widthDp < 600.dp) 1 else 2,
            useNavRail = widthClass == WidthClass.Expanded,
        )
    }
}

/** Center content and cap width on tablets / desktop-sized windows. */
@Composable
fun ResponsivePane(
    layout: ContentLayout = rememberContentLayout(),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (layout.maxContentWidth != null) {
                        Modifier.widthIn(max = layout.maxContentWidth)
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )
    }
}

fun Modifier.responsiveWidth(layout: ContentLayout): Modifier =
    this.fillMaxWidth().then(
        if (layout.maxContentWidth != null) Modifier.widthIn(max = layout.maxContentWidth) else Modifier,
    )
