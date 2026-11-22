package com.eightbee.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel

/** One tappable entry on a dashboard tab. */
class ToolLauncher(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val requiresFastboot: Boolean = false,
    val open: MainViewModel.() -> Unit
)

/** Renders [launchers] as cards separated by 16dp spacers, hiding fastboot-only entries when unsupported. */
@Composable
fun LauncherList(launchers: List<ToolLauncher>, supportsFastboot: Boolean, viewModel: MainViewModel) {
    launchers.filter { supportsFastboot || !it.requiresFastboot }.forEachIndexed { index, launcher ->
        if (index > 0) Spacer(modifier = Modifier.height(16.dp))
        LauncherCard(
            title = stringResource(launcher.titleRes),
            description = stringResource(launcher.descriptionRes),
            onClick = { launcher.open(viewModel) }
        )
    }
}

@Composable
fun LauncherCard(title: String, description: String, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        LauncherCardContent(title, description)
    }
}

/** Title + description body shared by every launcher card; [extra] renders below the description. */
@Composable
fun LauncherCardContent(
    title: String,
    description: String,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        extra()
    }
}
