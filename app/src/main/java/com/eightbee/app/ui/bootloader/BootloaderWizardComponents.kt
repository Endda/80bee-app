package com.eightbee.app.ui.bootloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared icon + title + optional subtitle header for bootloader wizard steps. */
@Composable
internal fun WizardIconHeader(
    icon: ImageVector,
    contentDescription: String,
    iconTint: Color,
    title: String,
    iconSize: Dp = 64.dp,
    iconBottomSpacing: Dp = 16.dp,
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    titleAlign: TextAlign = TextAlign.Center,
    titleBottomSpacing: Dp = 12.dp,
    subtitle: String? = null,
    subtitleAlign: TextAlign = TextAlign.Center,
    subtitleColor: Color = Color.Unspecified
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = iconTint,
        modifier = Modifier.size(iconSize)
    )
    Spacer(modifier = Modifier.height(iconBottomSpacing))
    Text(
        text = title,
        style = titleStyle,
        fontWeight = FontWeight.Bold,
        textAlign = titleAlign
    )
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(titleBottomSpacing))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = subtitleAlign,
            color = subtitleColor
        )
    }
}

/** Shared indeterminate progress view for rebooting and fastboot command execution steps. */
@Composable
internal fun WizardProgressStep(
    title: String,
    description: String
) {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Shared high-emphasis error-container warning card for destructive wizard steps. */
@Composable
internal fun WizardErrorBanner(
    text: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = textStyle,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = fontWeight
            )
        }
    }
}

/** Shared confirmation checkbox row with optional error emphasis. */
@Composable
internal fun WizardCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    emphasized: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.error else Color.Unspecified
        )
    }
}

/** Shared selectable radio option row for preset and custom fastboot commands. */
@Composable
internal fun WizardRadioOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Shared two-button action footer for wizard confirmation and selection steps. */
@Composable
internal fun WizardFooterButtons(
    secondaryLabel: String,
    onSecondaryClick: () -> Unit,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    primaryEnabled: Boolean = true,
    isDestructive: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onSecondaryClick,
            modifier = Modifier.weight(1f)
        ) {
            Text(secondaryLabel)
        }
        Button(
            onClick = onPrimaryClick,
            enabled = primaryEnabled,
            colors = if (isDestructive) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(primaryLabel)
        }
    }
}
