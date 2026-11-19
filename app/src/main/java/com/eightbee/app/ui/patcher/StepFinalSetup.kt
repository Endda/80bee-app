package com.eightbee.app.ui.patcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eightbee.app.MainViewModel
import com.eightbee.app.PixelMagiskPatcherState
import com.eightbee.app.R
import com.eightbee.app.ui.theme.SuccessGreen
import com.eightbee.app.ui.theme.SuccessGreenLight

@Composable
internal fun StepFinalSetup(viewModel: MainViewModel, state: PixelMagiskPatcherState, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SuccessGreenLight, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(40.dp)
            )
        }

        Text(
            text = stringResource(R.string.patcher_final_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.patcher_final_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StepInstructionRow(number = 1, text = stringResource(R.string.patcher_final_step1))
                StepInstructionRow(number = 2, text = stringResource(R.string.patcher_final_step2))
                StepInstructionRow(number = 3, text = stringResource(R.string.patcher_final_step3))

                Button(
                    onClick = { viewModel.installMagiskApkFromPatcher() },
                    enabled = !state.finalApkInstalled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (state.finalApkInstalled) R.string.patcher_final_apk_installed
                            else R.string.patcher_final_install_apk
                        )
                    )
                }

                StepInstructionRow(number = 4, text = stringResource(R.string.patcher_final_step4))
            }
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text(stringResource(R.string.patcher_return_to_tools))
        }
    }
}
