package com.termux.app.activities

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.termux.app.models.UserAction
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.ReportInfo
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.terminal.compose.TermuxExpressiveTheme
import com.termux.terminal.compose.settings.SettingsScreen
import com.termux.terminal.compose.settings.SettingsViewModel

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
                SettingsScreen(
                    viewModel = mViewModel,
                    onNavigateUp = { finish() },
                    onAboutClick = { openAbout() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mViewModel.reload()
    }

    private fun openAbout() {
        Thread {
            try {
                val title = "About"
                val aboutString = StringBuilder()
                aboutString.append(
                    TermuxUtils.getAppInfoMarkdownString(
                        this, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES
                    )
                )
                aboutString.append("\n\n")
                    .append(AndroidUtils.getDeviceInfoMarkdownString(this, true))
                aboutString.append("\n\n")
                    .append(TermuxUtils.getImportantLinksMarkdownString(this))

                val userActionName = UserAction.ABOUT.getName()
                val reportInfo = ReportInfo(
                    userActionName,
                    TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title
                )
                reportInfo.setReportString(aboutString.toString())
                reportInfo.setReportSaveFileLabelAndPath(
                    userActionName,
                    Environment.getExternalStorageDirectory().toString() + "/" +
                        FileUtils.sanitizeFileName(
                            TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log",
                            true, true
                        )
                )
                ReportActivity.startReportActivity(this, reportInfo)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open About", e)
            }
        }.start()
    }
}
