package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

@Composable
fun CopilotDisclaimerDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9500),
                modifier = Modifier.size(36.dp),
            )
        },
        title = { Text(stringResource(R.string.copilot_disclaimer_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (point in disclaimerPoints) {
                    DisclaimerBullet(stringResource(point))
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onAccept) {
                Text(stringResource(R.string.copilot_disclaimer_accept))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onCancel) {
                Text(stringResource(R.string.copilot_disclaimer_cancel))
            }
        },
    )
}

private val disclaimerPoints = listOf(
    R.string.copilot_disclaimer_p1,
    R.string.copilot_disclaimer_p2,
    R.string.copilot_disclaimer_p3,
    R.string.copilot_disclaimer_p4,
    R.string.copilot_disclaimer_p5,
    R.string.copilot_disclaimer_p6,
    R.string.copilot_disclaimer_p7,
)

@Composable
private fun DisclaimerBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
