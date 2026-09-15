package com.termux.app.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.termux.terminal.compose.TermuxExpressiveTheme
import com.termux.terminal.compose.settings.AboutScreen
import com.termux.terminal.compose.settings.LicensesScreen
import com.termux.terminal.compose.settings.SettingsScreen
import com.termux.terminal.compose.settings.SettingsViewModel

private enum class SettingsDestination {
    SETTINGS,
    ABOUT,
    LICENSES
}

/**
 * Compose replacement for {@link SettingsActivity}.
 *
 * Hosts the full settings hierarchy (Termux + terminal I/O + debugging +
 * plugin log levels + About). No Donate entry.
 */
class SettingsComposeActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "SettingsComposeActivity"
    }

    private lateinit var mViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        setContent {
            TermuxExpressiveTheme {
                var destination by rememberSaveable { mutableStateOf(SettingsDestination.SETTINGS) }
                when (destination) {
                    SettingsDestination.SETTINGS -> SettingsScreen(
                        viewModel = mViewModel,
                        onNavigateUp = { finish() },
                        onAboutClick = { destination = SettingsDestination.ABOUT }
                    )
                    SettingsDestination.ABOUT -> AboutScreen(
                        onBack = { destination = SettingsDestination.SETTINGS },
                        onNavigateToLicenses = { destination = SettingsDestination.LICENSES }
                    )
                    SettingsDestination.LICENSES -> LicensesScreen(
                        onBack = { destination = SettingsDestination.ABOUT }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mViewModel.reload()
    }
}
