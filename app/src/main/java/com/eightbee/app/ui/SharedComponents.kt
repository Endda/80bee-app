package com.eightbee.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreenScaffold(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .let { base ->
                if (scrollable) {
                    base.padding(16.dp).verticalScroll(rememberScrollState())
                } else {
                    base
                }
            }
        Column(modifier = modifier, content = content)
    }
}

@Composable
fun ToolIntroCard(
    title: String,
    description: String
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class AlertTone {
    Error,
    Info
}

private data class AlertColors(
    val container: Color,
    val content: Color,
    val title: Color,
    val iconTint: Color,
    val icon: ImageVector
)

@Composable
private fun alertColors(tone: AlertTone): AlertColors = when (tone) {
    AlertTone.Error -> AlertColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        title = MaterialTheme.colorScheme.error,
        iconTint = MaterialTheme.colorScheme.error,
        icon = Icons.Filled.Warning
    )
    AlertTone.Info -> AlertColors(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        title = MaterialTheme.colorScheme.onSecondaryContainer,
        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.Filled.Info
    )
}

@Composable
fun AlertBanner(
    message: String,
    tone: AlertTone,
    modifier: Modifier = Modifier,
    title: String? = null,
    iconContentDescription: String? = null,
    prominent: Boolean = false
) {
    val colors = alertColors(tone)
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.container),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(if (prominent) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconContentDescription != null) {
                Icon(
                    imageVector = colors.icon,
                    contentDescription = iconContentDescription,
                    tint = colors.iconTint
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.title
                    )
                }
                Text(
                    text = message,
                    style = if (prominent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = colors.content
                )
            }
        }
    }
}
