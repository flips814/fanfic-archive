package org.fanficarchive.android.screens.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Material 3 outlined text field with floating label.
 */
@Composable
fun FanficTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    /** Applied to the OutlinedTextField (e.g. ExposedDropdownMenuBox menuAnchor). */
    textFieldModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .then(textFieldModifier),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        isError = isError,
        textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge),
        label = label?.takeIf { it.isNotBlank() }?.let {
            { Text(it, style = MaterialTheme.typography.bodyLarge) }
        },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
        supportingText = supportingText?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.extraSmall,
        interactionSource = interactionSource,
    )
}
