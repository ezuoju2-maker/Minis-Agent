package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    onDismiss: () -> Unit = onDismissRequest,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    neutralColor: Color? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                if (text != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val confirmColor = if (isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
                if (neutralText != null && onNeutral != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                        MinisTextButton(onClick = onNeutral) {
                            Text(text = neutralText, color = neutralColor ?: confirmColor)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                    }
                }
            }
        }
    }
}
