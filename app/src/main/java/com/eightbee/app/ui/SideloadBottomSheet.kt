package com.eightbee.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.InstallStep
import com.eightbee.app.R
import com.eightbee.app.SideloadInstallOptions
import com.eightbee.app.SideloadState
import com.eightbee.app.features.SideloadController
import com.eightbee.app.ui.theme.SuccessGreen
import java.util.Locale

private data class SideloadOptionSpec(
    @param:StringRes @get:StringRes val titleRes: Int,
    val descriptionRes: (isTargetAndroid14OrHigher: Boolean) -> Int,
    val isChecked: (SideloadInstallOptions) -> Boolean,
    val isEnabled: (isTargetAndroid14OrHigher: Boolean) -> Boolean = { true },
    val onCheckedChange: ((SideloadInstallOptions, Boolean) -> SideloadInstallOptions)? = null
)

private val PRIMARY_SIDELOAD_OPTIONS = listOf(
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_replace_title,
        descriptionRes = { R.string.sideload_opt_replace_desc },
        isChecked = { true },
        isEnabled = { false },
        onCheckedChange = null
    ),
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_bypass_sdk_title,
        descriptionRes = { isAndroid14Plus ->
            if (isAndroid14Plus) R.string.sideload_opt_bypass_sdk_supported
            else R.string.sideload_opt_bypass_sdk_unsupported
        },
        isChecked = { it.bypassLowTargetSdk },
        isEnabled = { isAndroid14Plus -> isAndroid14Plus },
        onCheckedChange = { opts, checked -> opts.copy(bypassLowTargetSdk = checked) }
    ),
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_grant_title,
        descriptionRes = { R.string.sideload_opt_grant_desc },
        isChecked = { it.grantAllPermissions },
        onCheckedChange = { opts, checked -> opts.copy(grantAllPermissions = checked) }
    )
)

private val ADVANCED_SIDELOAD_OPTIONS = listOf(
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_downgrade_title,
        descriptionRes = { R.string.sideload_opt_downgrade_desc },
        isChecked = { it.allowDowngrade },
        onCheckedChange = { opts, checked -> opts.copy(allowDowngrade = checked) }
    ),
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_test_title,
        descriptionRes = { R.string.sideload_opt_test_desc },
        isChecked = { it.testOnly },
        onCheckedChange = { opts, checked -> opts.copy(testOnly = checked) }
    ),
    SideloadOptionSpec(
        titleRes = R.string.sideload_opt_sdcard_title,
        descriptionRes = { R.string.sideload_opt_sdcard_desc },
        isChecked = { it.installToSdCard },
        onCheckedChange = { opts, checked -> opts.copy(installToSdCard = checked) }
    )
)

private val SIDELOAD_TROUBLESHOOTING_TIPS = listOf(
    R.string.sideload_tip_1,
    R.string.sideload_tip_2,
    R.string.sideload_tip_3,
    R.string.sideload_tip_4,
    R.string.sideload_tip_5
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideloadBottomSheet(
    controller: SideloadController,
    onDismiss: () -> Unit
) {
    val sideloadState by controller.state.collectAsState()
    val dismissAndClear = {
        controller.clear()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (sideloadState !is SideloadState.Installing &&
                sideloadState !is SideloadState.CopyingAndParsing
            ) {
                dismissAndClear()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = sideloadState) {
                is SideloadState.Idle -> {
                    Text(stringResource(R.string.sideload_idle_prompt), style = MaterialTheme.typography.bodyLarge)
                }
                is SideloadState.CopyingAndParsing -> {
                    ParsingView()
                }
                is SideloadState.ConfigReady -> {
                    ConfigView(
                        config = state,
                        onInstall = { options -> controller.executeInstall(state, options) },
                        onCancel = dismissAndClear
                    )
                }
                is SideloadState.Installing -> {
                    InstallingView(state = state)
                }
                is SideloadState.Success -> {
                    SuccessView(appLabel = state.appLabel, onDismiss = dismissAndClear)
                }
                is SideloadState.Error -> {
                    ErrorView(message = state.message, step = state.step, onDismiss = dismissAndClear)
                }
            }
        }
    }
}

@Composable
fun ParsingView() {
    Spacer(modifier = Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.sideload_preparing_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(R.string.sideload_preparing_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun ConfigView(
    config: SideloadState.ConfigReady,
    onInstall: (SideloadInstallOptions) -> Unit,
    onCancel: () -> Unit
) {
    val isTargetAndroid14OrHigher = config.targetDeviceSdk >= 34
    var options by remember(config.targetDeviceSdk) {
        mutableStateOf(SideloadInstallOptions(bypassLowTargetSdk = isTargetAndroid14OrHigher))
    }
    var advancedExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (config.appIcon != null) {
            Image(
                bitmap = config.appIcon.asImageBitmap(),
                contentDescription = stringResource(R.string.sideload_cd_app_icon),
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = stringResource(R.string.sideload_cd_app_icon_placeholder),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = config.appLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = config.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.sideload_version_format, config.versionName)) }
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text(formatSize(config.totalSize)) }
                )
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    if (config.isArchive) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.sideload_cd_archive_info),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.sideload_split_bundle,
                        config.apkFiles.size,
                        config.apkFiles.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    SideloadOptionGroup(
        specs = PRIMARY_SIDELOAD_OPTIONS,
        options = options,
        isTargetAndroid14OrHigher = isTargetAndroid14OrHigher,
        onOptionsChange = { options = it }
    )

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(
        onClick = { advancedExpanded = !advancedExpanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sideload_advanced_options))
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.sideload_cd_expand_advanced),
                modifier = Modifier.rotate(if (advancedExpanded) 180f else 0f)
            )
        }
    }

    AnimatedVisibility(visible = advancedExpanded) {
        SideloadOptionGroup(
            specs = ADVANCED_SIDELOAD_OPTIONS,
            options = options,
            isTargetAndroid14OrHigher = isTargetAndroid14OrHigher,
            onOptionsChange = { options = it },
            modifier = Modifier.padding(start = 16.dp)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = {
                onInstall(
                    options.copy(
                        bypassLowTargetSdk = options.bypassLowTargetSdk && isTargetAndroid14OrHigher
                    )
                )
            },
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.sideload_install_button))
        }
    }
}

@Composable
private fun SideloadOptionGroup(
    specs: List<SideloadOptionSpec>,
    options: SideloadInstallOptions,
    isTargetAndroid14OrHigher: Boolean,
    onOptionsChange: (SideloadInstallOptions) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            val enabled = spec.isEnabled(isTargetAndroid14OrHigher)
            SideloadOptionRow(
                title = stringResource(spec.titleRes),
                description = stringResource(spec.descriptionRes(isTargetAndroid14OrHigher)),
                checked = spec.isChecked(options),
                enabled = enabled,
                onCheckedChange = spec.onCheckedChange?.let { update ->
                    { checked ->
                        if (enabled) onOptionsChange(update(options, checked))
                    }
                }
            )
        }
    }
}

@Composable
private fun SideloadOptionRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InstallingView(state: SideloadState.Installing) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.sideload_installing_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start
    )
    Spacer(modifier = Modifier.height(16.dp))

    InstallStep.entries.forEach { step ->
        val isCompleted = state.completedSteps.contains(step)
        val isCurrent = state.currentStep == step

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.sideload_cd_step_completed),
                        tint = SuccessGreen
                    )
                } else if (isCurrent) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.sideload_cd_step_pending),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(step.labelResId()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else if (isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
                if (isCurrent && step == InstallStep.STREAM_APKS && state.currentFileProgress.isNotEmpty()) {
                    Text(
                        text = state.currentFileProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
fun SuccessView(appLabel: String, onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = stringResource(R.string.cd_success),
        tint = SuccessGreen,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.sideload_success_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = stringResource(R.string.sideload_success_desc_format, appLabel),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 12.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.action_done))
    }
}

@Composable
fun ErrorView(message: String, step: InstallStep, onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.cd_error),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.sideload_failed_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error
    )
    Text(
        text = stringResource(R.string.sideload_failed_step_format, stringResource(step.labelResId())),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
            fontFamily = FontFamily.Monospace
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.sideload_tips_header),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        SIDELOAD_TROUBLESHOOTING_TIPS.forEach { tipRes ->
            BulletItem(stringResource(tipRes))
        }
    }

    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.action_close))
    }
}

@Composable
fun BulletItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.2f MB", mb)
    } else {
        String.format(Locale.US, "%.2f KB", kb)
    }
}
