package com.termux.terminal.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.termux.R

/**
 * Full-screen Debian rootfs installer overlay (Fase 3).
 *
 * Shown instead of the terminal while the Debian rootfs is downloading,
 * verifying or extracting. On failure it offers retry and failsafe options.
 *
 * @param viewModel The TermuxViewModel instance holding installer state
 * @param onRetry Callback to restart installation
 * @param onFailsafe Callback to open a failsafe shell instead
 * @param modifier Modifier to apply
 */
@Composable
fun DebianInstallerScreen(
    viewModel: TermuxViewModel,
    onRetry: () -> Unit,
    onFailsafe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val installer = uiState.debianInstaller

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.debian_installer_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (installer.progress != null) {
                LinearProgressIndicator(
                    progress = { installer.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = installer.statusText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (installer.error != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = installer.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.debian_installer_retry))
                    }
                    OutlinedButton(onClick = onFailsafe) {
                        Text(stringResource(R.string.debian_installer_failsafe))
                    }
                }
            }
        }
    }
}
