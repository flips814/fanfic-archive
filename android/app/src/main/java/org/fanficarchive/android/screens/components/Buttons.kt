package org.fanficarchive.android.screens.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ButtonMinHeight = 48.dp
private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = ButtonMinHeight)
            .heightIn(min = ButtonMinHeight),
        contentPadding = ButtonPadding,
        content = { ButtonLabelRow(content) },
    )
}

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (filled) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .defaultMinSize(minHeight = ButtonMinHeight)
                .heightIn(min = ButtonMinHeight),
            contentPadding = ButtonPadding,
            content = { ButtonLabelRow(content) },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .defaultMinSize(minHeight = ButtonMinHeight)
                .heightIn(min = ButtonMinHeight),
            contentPadding = ButtonPadding,
            content = { ButtonLabelRow(content) },
        )
    }
}

@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        content = { ButtonLabelRow(content) },
    )
}

@Composable
fun ToggleActionButton(
    pressed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (pressed) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .defaultMinSize(minHeight = ButtonMinHeight)
                .heightIn(min = ButtonMinHeight),
            contentPadding = ButtonPadding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = { ButtonLabelRow(content) },
        )
    } else {
        SecondaryButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
private fun ButtonLabelRow(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ButtonLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}
