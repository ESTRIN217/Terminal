package com.termux.app.activities

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.terminal.compose.TerminalFontCatalog
import com.termux.terminal.compose.TerminalFontImporter
import com.termux.terminal.compose.TerminalFontLoader
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
    private lateinit var mPreferences: TermuxAppSharedPreferences

    /**
     * Incremented on every resume. Reading it from composition makes the app UI font
     * reload when returning from the system font picker (font import).
     */
    private var mFontRevision by mutableStateOf(0)

    /** System file picker for importing a font from shared storage into ~/.termux/font.ttf. */
    private val mFontPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val error = TerminalFontImporter.importFont(this, uri)
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Font import failed\n" + error)
                Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_LONG).show()
            } else {
                mViewModel.setTerminalFont(TerminalFontCatalog.CUSTOM_FONT_ID)
                Toast.makeText(this, R.string.font_import_ok, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        mPreferences = TermuxAppSharedPreferences.build(this, true)

        setContent {
            // The whole settings UI mirrors the terminal font.
            mFontRevision
            val terminalTypeface = TerminalFontLoader.resolve(this, mPreferences.getTerminalFont())
            TermuxExpressiveTheme(terminalTypeface = terminalTypeface) {
                var destination by rememberSaveable { mutableStateOf(SettingsDestination.SETTINGS) }
                when (destination) {
                    SettingsDestination.SETTINGS -> SettingsScreen(
                        viewModel = mViewModel,
                        onNavigateUp = { finish() },
                        onAboutClick = { destination = SettingsDestination.ABOUT },
                        onImportFont = { mFontPickerLauncher.launch(TerminalFontImporter.PICKER_MIME_TYPES) }
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
        mFontRevision++
    }
}
