package com.termux.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.ContextMenu
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ListView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsComposeActivity
import com.termux.app.api.file.FileReceiverActivity
import com.termux.app.models.UserAction
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.shell.ShellUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.data.TermuxUrlUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.ComposeTerminalSessionClient
import com.termux.terminal.compose.ComposeTerminalViewClient
import com.termux.terminal.compose.DebianInstallerScreen
import com.termux.terminal.compose.ExtraKeysConfig
import com.termux.terminal.compose.TerminalColorSchemeLoader
import com.termux.terminal.compose.TerminalFontCatalog
import com.termux.terminal.compose.TerminalFontImporter
import com.termux.terminal.compose.TerminalFontLoader
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TermuxExpressiveTheme
import com.termux.terminal.compose.TermuxMainScreen
import com.termux.terminal.compose.TermuxSessionUiModel
import com.termux.terminal.compose.TermuxViewModel
import com.termux.terminal.compose.TerminalViewRegistry
import androidx.activity.enableEdgeToEdge
import java.io.File

/**
 * A terminal emulator activity using Jetpack Compose.
 *
 * This activity provides a modern UI for the terminal emulator using:
 * - Jetpack Compose for UI rendering
 * - The native [com.termux.view.TerminalView] hosted via AndroidView for terminal display
 * - Material Design 3 for UI components
 *
 * The activity binds to [TermuxService] for session management and uses
 * [TermuxViewModel] for UI state management.
 */
class TermuxComposeActivity : ComponentActivity(), ServiceConnection {

    companion object {
        private const val LOG_TAG = "TermuxComposeActivity"
        private const val MAX_SESSIONS = 8

        private const val CONTEXT_MENU_SELECT_URL_ID = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_STYLING_ID = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_REPORT_ID = 9

        /**
         * Build a launch intent for the Compose activity.
         *
         * @param context The [Context] to create the intent with
         * @return An [Intent] targeting [TermuxComposeActivity]
         */
        @JvmStatic
        fun newInstance(context: Context): Intent =
            Intent(context, TermuxComposeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Start the Compose activity, bringing it to the foreground.
         *
         * @param context The [Context] to start the activity from
         */
        @JvmStatic
        fun startTermuxActivity(context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        /**
         * Broadcast a style reload request to the Compose activity.
         *
         * Works as a proxy for the activity and is called when a new session executes
         * (see {@link TermuxService#createTermuxSession(ExecutionCommand)}).
         *
         * @param context The [Context] to send the broadcast from
         * @param recreateActivity Whether the activity should be recreated after reloading
         */
        @JvmStatic
        fun updateTermuxActivityStyling(context: Context, recreateActivity: Boolean) {
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
                .putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            context.sendBroadcast(stylingIntent)
        }
    }

    private var mTermuxService: TermuxService? = null
    private var mIsBound = false
    private var mIsVisible = false
    private var mIsActivityRecreated = false

    /** Compose state mirror of the keep-screen-on preference so the menu checkmark updates. */
    private var mIsKeepScreenOnEnabled by mutableStateOf(false)

    /**
     * Incremented on every resume. Reading it from composition makes the terminal palette
     * recompute when returning from the Settings screen (custom color scheme toggle).
     */
    private var mPaletteRevision by mutableStateOf(0)

    /**
     * Incremented on every resume. Reading it from composition makes the terminal font and the
     * ligature setting reload when returning from the Settings screen (font selector).
     */
    private var mFontRevision by mutableStateOf(0)

    private lateinit var mProperties: TermuxAppSharedProperties
    private lateinit var mPreferences: TermuxAppSharedPreferences

    private lateinit var mViewModel: TermuxViewModel
    private lateinit var mTerminalSessionClient: ComposeTerminalSessionClient
    private lateinit var mTerminalViewClient: ComposeTerminalViewClient

    /** Whether the Volume Down key is currently held (virtual Ctrl). */
    private var mVirtualControlKeyDown = false

    /** Whether the Volume Up key is currently held (virtual Fn). */
    private var mVirtualFnKeyDown = false

    /** Failsafe flag pending a Debian install (used by installer retry). */
    private var mPendingFailSafe = false

    /** Receiver for style-reload, crash and storage-permission broadcasts while visible. */
    private var mTermuxActivityBroadcastReceiver: BroadcastReceiver? = null

    /** System file picker for importing a font from shared storage into ~/.termux/font.ttf. */
    private val mFontPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val error = TerminalFontImporter.importFont(this, uri)
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Font import failed\n" + error)
                Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_LONG).show()
            } else {
                mPreferences.setTerminalFont(TerminalFontCatalog.CUSTOM_FONT_ID)
                // Reload the Typeface in the active terminal via the composition.
                mFontRevision++
                Toast.makeText(this, R.string.font_import_ok, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Logger.logDebug(LOG_TAG, "onCreate")

        mIsActivityRecreated = savedInstanceState?.getBoolean("activity_recreated", false) ?: false

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        mProperties = TermuxAppSharedProperties.getProperties()
        mPreferences = TermuxAppSharedPreferences.build(this, true)
        mViewModel = ViewModelProvider(this)[TermuxViewModel::class.java]
        mTerminalSessionClient = ComposeTerminalSessionClient(mViewModel, ::removeSession)
        mTerminalViewClient = ComposeTerminalViewClient(mViewModel, mProperties, ::removeSession)

        // Apply keep screen on flag if previously enabled via the more options menu
        mIsKeepScreenOnEnabled = mPreferences.shouldKeepScreenOn()
        if (mIsKeepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Load configurations from preferences into the ViewModel
        loadExtraKeysConfig()
        mViewModel.setExtraKeysVisible(mPreferences.shouldShowTerminalToolbar())
        mViewModel.setFontSize(mPreferences.getFontSize().toFloat())
        mViewModel.setNativeRenderer(mPreferences.isNativeComposeRendererEnabled())

        val serviceIntent = Intent(this, TermuxService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, this, BIND_AUTO_CREATE)

        setContent {
            // The terminal Typeface is resolved before the theme so the whole app UI
            // mirrors the terminal font. mFontRevision forces a reload after Settings.
            mFontRevision
            val terminalTypeface = TerminalFontLoader.resolve(this, mPreferences.getTerminalFont())
            TermuxExpressiveTheme(terminalTypeface = terminalTypeface) {
                mPaletteRevision
                mFontRevision
                val customColorScheme =
                    if (mPreferences.shouldUseCustomColorScheme()) TerminalColorSchemeLoader.load() else null
                val palette = TerminalPalette.fromTheme(customColorScheme)
                val enableLigatures = mPreferences.isTerminalFontLigaturesEnabled()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by mViewModel.uiState.collectAsState()
                    if (uiState.debianInstaller.visible) {
                        DebianInstallerScreen(
                            viewModel = mViewModel,
                            onRetry = { startDebianInstall(mPendingFailSafe) },
                            onFailsafe = {
                                mViewModel.hideDebianInstaller()
                                addNewSession(true, null)
                            }
                        )
                    } else {
                    TermuxMainScreen(
                        viewModel = mViewModel,
                        viewClient = mTerminalViewClient,
                        palette = palette,
                        typeface = terminalTypeface,
                        enableLigatures = enableLigatures,
                        isKeepScreenOnEnabled = mIsKeepScreenOnEnabled,
                        onSetKeepScreenOn = { enabled -> setKeepScreenOn(enabled) },
                        onOpenHelp = {
                            ActivityUtils.startActivity(
                                this@TermuxComposeActivity,
                                Intent(this@TermuxComposeActivity, HelpActivity::class.java)
                            )
                        },
                        onCreateSession = { addNewSession(false, null) },
                        onRemoveSession = { session -> removeSession(session) },
                        onToggleKeyboard = { toggleKeyboard() },
                        onOpenFileManager = {
                            if (mViewModel.uiState.value.sessions.size >= MAX_SESSIONS) {
                                Toast.makeText(this, R.string.title_max_terminals_reached, Toast.LENGTH_SHORT).show()
                            } else {
                                mViewModel.createFileManagerSession(getString(R.string.title_activity_file_manager))
                            }
                        },
                        onOpenInTerminal = { openTerminalIn(it) },
                        onOpenSettings = {
                            ActivityUtils.startActivity(
                                this@TermuxComposeActivity,
                                Intent(this@TermuxComposeActivity, SettingsComposeActivity::class.java)
                            )
                        }
                    )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Logger.logDebug(LOG_TAG, "onStart")
        mIsVisible = true
        registerTermuxActivityBroadcastReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("activity_recreated", true)
    }

    override fun onResume() {
        super.onResume()
        Logger.logDebug(LOG_TAG, "onResume")

        // Sync SharedPreferences → TermuxViewModel (bridge from Settings screen)
        mViewModel.setExtraKeysVisible(mPreferences.shouldShowTerminalToolbar())
        mViewModel.setFontSize(mPreferences.getFontSize().toFloat())
        mViewModel.setNativeRenderer(mPreferences.isNativeComposeRendererEnabled())
        // Recompute the terminal palette (custom color scheme may have changed in Settings).
        mPaletteRevision++
        // Reload the terminal font and ligature setting (may have changed in Settings).
        mFontRevision++

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)
    }

    override fun onPause() {
        super.onPause()
        Logger.logDebug(LOG_TAG, "onPause")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Logger.logDebug(LOG_TAG, "onConfigurationChanged: ${newConfig.orientation}")
        // WebView will auto-resize via Compose layout; trigger fit after layout
        mViewModel.uiState.value.activeSession?.let { session ->
            val cols = session.getEmulator()?.mColumns ?: return
            val rows = session.getEmulator()?.mRows ?: return
            Logger.logDebug(LOG_TAG, "Terminal size: ${cols}x${rows}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (Intent.ACTION_RUN == action) {
            val isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
            if (mTermuxService != null) {
                // Consume the shortcut request so a later activity recreation
                // (rotation) does not re-process the same intent.
                intent.action = null
                addNewSession(isFailSafe, null)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Logger.logDebug(LOG_TAG, "onStop")
        mIsVisible = false
        unregisterTermuxActivityBroadcastReceiver()

        // Save current session handle for restoration after rotation
        val session = mViewModel.uiState.value.activeSession
        if (session != null) {
            mPreferences.setCurrentSession(session.mHandle)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.logDebug(LOG_TAG, "onDestroy")

        mTermuxService?.unsetComposeTerminalSessionClient()
        mTermuxService = null

        if (mIsBound) {
            try {
                unbindService(this)
            } catch (e: Exception) {
                Logger.logDebug(LOG_TAG, "Error unbinding service: ${e.message}")
            }
            mIsBound = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Handle volume keys as virtual Ctrl/Fn
        if (handleVirtualKeys(keyCode, event, down = true)) {
            return true
        }

        // Handle Ctrl+Alt shortcuts
        if (handleCtrlAltShortcuts(keyCode, event)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleVirtualKeys(keyCode, event, down = false)) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    /**
     * Handle volume keys as virtual modifier keys.
     * Volume Down = Ctrl, Volume Up = Fn.
     */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        if (mProperties.areVirtualVolumeKeysDisabled()) {
            return false
        }

        // Don't steal from full external keyboards
        val inputDevice = event.device
        if (inputDevice != null && inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                mVirtualControlKeyDown = down
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                mVirtualFnKeyDown = down
                true
            }
            else -> false
        }
    }

    /**
     * Handle Ctrl+Alt keyboard shortcuts for session management.
     */
    private fun handleCtrlAltShortcuts(keyCode: Int, event: KeyEvent): Boolean {
        val ctrlDown = event.isCtrlPressed
        val altDown = event.isAltPressed

        if (!ctrlDown || !altDown) return false

        when (keyCode) {
            KeyEvent.KEYCODE_N -> {
                addNewSession(false, null)
                return true
            }
            KeyEvent.KEYCODE_P -> {
                mViewModel.previousSession()
                return true
            }
            KeyEvent.KEYCODE_K -> {
                toggleKeyboard()
                return true
            }
            KeyEvent.KEYCODE_C -> {
                addNewSession(false, null)
                return true
            }
            KeyEvent.KEYCODE_V -> {
                pasteFromClipboard()
                return true
            }
            KeyEvent.KEYCODE_W -> {
                val model = mViewModel.uiState.value.activeSessionModel
                if (model != null) {
                    removeSession(model)
                }
                return true
            }
            KeyEvent.KEYCODE_M -> {
                mViewModel.toggleDrawer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                mViewModel.setDrawerOpen(true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                mViewModel.setDrawerOpen(false)
                return true
            }
            KeyEvent.KEYCODE_MINUS -> {
                adjustFontSize(-1f)
                return true
            }
            KeyEvent.KEYCODE_EQUALS -> {
                adjustFontSize(1f)
                return true
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val sessionIndex = keyCode - KeyEvent.KEYCODE_1
                mViewModel.switchSession(sessionIndex)
                return true
            }
            KeyEvent.KEYCODE_0 -> {
                mViewModel.switchSession(9)
                return true
            }
        }

        return false
    }

    /**
     * Get the virtual Ctrl key state (from Volume Down).
     */
    fun isVirtualControlKeyDown(): Boolean = mVirtualControlKeyDown

    /**
     * Get the virtual Fn key state (from Volume Up).
     */
    fun isVirtualFnKeyDown(): Boolean = mVirtualFnKeyDown

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        val binder = service as? TermuxService.LocalBinder ?: return
        mTermuxService = binder.service
        mIsBound = true

        // Take over session client callbacks from the service client so that the
        // TerminalView gets screen update notifications
        mTermuxService?.setComposeTerminalSessionClient(mTerminalSessionClient)

        if (mTermuxService?.isTermuxSessionsEmpty == true) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(this) {
                    if (mTermuxService == null) return@setupBootstrapIfNeeded
                    // Handle initial intent (e.g., shortcuts with ACTION_RUN)
                    val launchIntent = intent
                    val isFailSafe = launchIntent?.getBooleanExtra(
                        TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false
                    ) ?: false
                    // Consume the shortcut request so a later activity recreation
                    // (rotation) does not re-process the same intent.
                    if (launchIntent != null && Intent.ACTION_RUN == launchIntent.action) {
                        launchIntent.action = null
                    }
                    ensureProotAndDebian(isFailSafe)
                }
            }
        } else {
            val svc = mTermuxService ?: return
            val sessionsCount = svc.getTermuxSessionsSize()
            for (i in 0 until sessionsCount) {
                val termuxSession = svc.getTermuxSession(i)
                if (termuxSession != null) {
                    val terminalSession = termuxSession.getTerminalSession()
                    // Idempotent: skip sessions already present in the UI (rotation recovery
                    // keeps the ViewModel alive, so re-seeding without a guard would duplicate).
                    val alreadyPresent = mViewModel.uiState.value.sessions.any {
                        it is TermuxSessionUiModel.Terminal && it.session == terminalSession
                    }
                    if (!alreadyPresent) {
                        val sessionName = termuxSession.getExecutionCommand()?.shellName ?: "Session ${i + 1}"
                        mViewModel.addSession(terminalSession, sessionName)
                    }
                }
            }

            // Restore active session from saved handle (rotation recovery)
            if (mIsActivityRecreated) {
                val savedHandle = mPreferences.currentSession
                if (savedHandle != null) {
                    val savedSession = svc.getTerminalSessionForHandle(savedHandle)
                    if (savedSession != null) {
                        val state = mViewModel.uiState.value
                        val index = state.sessions.indexOfFirst {
                            it is TermuxSessionUiModel.Terminal && it.session == savedSession
                        }
                        if (index >= 0) {
                            mViewModel.switchSession(index)
                        }
                    }
                }
            }

            // Honor a shortcut intent delivered on a cold start when sessions already
            // exist (the bootstrap path above only runs when the service has no sessions).
            // handleIntent no-ops for non ACTION_RUN intents and consumes the action so
            // recreation does not re-process it.
            handleIntent(intent)
        }

        // Notify other apps that Termux opened
        TermuxUtils.sendTermuxOpenedBroadcast(this)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")
        mTermuxService = null
        mIsBound = false
    }

    /**
     * Ensure the bundled proot binary and the Debian rootfs are installed
     * before opening the first session (Fase 3).
     *
     * @param isFailSafe Whether a failsafe session was requested
     */
    private fun ensureProotAndDebian(isFailSafe: Boolean) {
        mPendingFailSafe = isFailSafe
        Thread {
            val prootError = TermuxInstaller.installProotIfNeeded(this)
            runOnUiThread {
                if (prootError != null) {
                    showProotErrorDialog(prootError)
                } else if (isFailSafe || DebianInstaller.isInstalled()) {
                    addNewSession(isFailSafe, null)
                } else {
                    startDebianInstall(isFailSafe)
                }
            }
        }.start()
    }

    private fun showProotErrorDialog(error: Error) {
        Logger.logError(LOG_TAG, Error.getMinimalErrorString(error))
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.bootstrap_error_title)
            .setMessage(Error.getMinimalErrorString(error))
            .setNegativeButton(R.string.bootstrap_error_abort) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setPositiveButton(R.string.bootstrap_error_try_again) { dialog, _ ->
                dialog.dismiss()
                ensureProotAndDebian(mPendingFailSafe)
            }
            .show()
    }

    /**
     * Start (or attach to) the Debian rootfs installation with Compose progress.
     *
     * @param isFailSafe Whether to open a failsafe session after install
     */
    private fun startDebianInstall(isFailSafe: Boolean) {
        mPendingFailSafe = isFailSafe
        mViewModel.showDebianInstaller()
        DebianInstaller.install(this, object : DebianInstaller.Listener {
            override fun onProgress(phase: DebianInstaller.Phase, done: Long, total: Long) {
                val progress = if (total > 0) done.toFloat() / total.toFloat() else null
                val status = when (phase) {
                    DebianInstaller.Phase.DOWNLOADING ->
                        getString(R.string.debian_installer_downloading, formatMB(done) + " / " + formatMB(total))
                    DebianInstaller.Phase.VERIFYING, DebianInstaller.Phase.CONFIGURING,
                    DebianInstaller.Phase.MOVING ->
                        getString(R.string.debian_installer_configuring)
                    DebianInstaller.Phase.EXTRACTING ->
                        getString(R.string.debian_installer_extracting, done)
                }
                mViewModel.updateDebianInstallerProgress(progress, status)
            }

            override fun onFinished() {
                runOnUiThread {
                    mViewModel.hideDebianInstaller()
                    if (mTermuxService != null) addNewSession(isFailSafe, null)
                }
            }

            override fun onError(error: Error) {
                runOnUiThread {
                    mViewModel.setDebianInstallerError(Error.getMinimalErrorString(error))
                }
            }
        })
    }

    private fun formatMB(bytes: Long): String {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mTermuxService ?: return

        if (mViewModel.uiState.value.sessions.size >= MAX_SESSIONS) {
            Toast.makeText(this, R.string.title_max_terminals_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val currentSession = getCurrentSession()
        // A failsafe session runs a host (/system/bin/sh) shell but intentionally
        // starts in the Debian guest home on the host filesystem (files/debian/root).
        val workingDirectory = if (isFailSafe) {
            TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH
        } else {
            currentSession?.getCwd() ?: mProperties.getDefaultWorkingDirectory()
        }

        val termuxSession = service.createTermuxSession(
            null, null, null, workingDirectory, isFailSafe, sessionName
        ) ?: return

        val terminalSession = termuxSession.getTerminalSession()
        val name = sessionName ?: "Session ${service.getTermuxSessionsSize()}"

        mViewModel.addSession(terminalSession, name)
    }

    /**
     * Open a new terminal session in a directory chosen from the file manager.
     *
     * @param workingDirectory Host path used as the new session working directory
     */
    private fun openTerminalIn(workingDirectory: String) {
        val service = mTermuxService ?: return

        if (mViewModel.uiState.value.sessions.size >= MAX_SESSIONS) {
            Toast.makeText(this, R.string.title_max_terminals_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val dir = File(workingDirectory)
        if (!dir.isDirectory) {
            Logger.logError(LOG_TAG, "Open in terminal: not a directory: $workingDirectory")
            return
        }

        val termuxSession = service.createTermuxSession(
            null, null, null, workingDirectory, false, null
        ) ?: return

        val terminalSession = termuxSession.getTerminalSession()
        val name = "Session ${service.getTermuxSessionsSize()}"

        mViewModel.addSession(terminalSession, name)
    }

    /**
     * Remove a terminal session from the UI and the service.
     *
     * Delegates from the typed overload [removeSession] via
     * [com.termux.terminal.compose.ComposeTerminalSessionClient] / ViewClient callbacks.
     */
    private fun removeSession(session: TerminalSession) {
        val model = mViewModel.uiState.value.sessions.find {
            it is TermuxSessionUiModel.Terminal && it.session == session
        } ?: return
        removeSession(model)
    }

    /**
     * Remove any session (terminal or file manager) from the UI.
     *
     * Terminal sessions are killed/removed from [TermuxService] first; file manager sessions
     * are removed from the UI only.
     */
    private fun removeSession(model: TermuxSessionUiModel) {
        if (model is TermuxSessionUiModel.Terminal) {
            val session = model.session
            val service = mTermuxService
            if (service != null) {
                val termuxSession = service.getTermuxSessionForTerminalSession(session)
                if (termuxSession != null) {
                    if (session.isRunning()) {
                        termuxSession.killIfExecuting(this, true)
                    } else {
                        service.removeTermuxSession(session)
                    }
                }
            }
        }

        mViewModel.removeSession(model)

        if (mViewModel.uiState.value.sessions.isEmpty()) {
            finish()
        }
    }

    private fun getCurrentSession(): TerminalSession? {
        return mViewModel.uiState.value.activeSession
    }

    private fun toggleKeyboard() {
        KeyboardUtils.toggleSoftKeyboard(this)
    }

    /**
     * Persist and apply the keep-screen-on preference.
     *
     * @param enabled Whether the screen should be kept on while the activity is visible
     */
    private fun setKeepScreenOn(enabled: Boolean) {
        mIsKeepScreenOnEnabled = enabled
        mPreferences.setKeepScreenOn(enabled)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (text != null) {
                val session = mViewModel.uiState.value.activeSession
                session?.write(text)
            }
        }
    }

    private fun adjustFontSize(delta: Float) {
        val currentSize = mViewModel.uiState.value.fontSize
        val newSize = (currentSize + delta).coerceIn(8f, 32f)
        mViewModel.setFontSize(newSize)
    }

    private fun loadExtraKeysConfig() {
        try {
            val extraKeysJson = mProperties.getInternalPropertyValue(
                TermuxPropertyConstants.KEY_EXTRA_KEYS, true
            ) as? String
            if (extraKeysJson != null) {
                val config = ExtraKeysConfig.parse(extraKeysJson)
                mViewModel.setExtraKeysConfig(config)
            }
        } catch (e: Exception) {
            Logger.logDebug(LOG_TAG, "Failed to load extra keys config: ${e.message}")
        }
    }

    /**
     * Register the broadcast receiver for app-wide broadcasts that previously
     * targeted the classic activity: style reloads, app crash notifications and
     * storage permission requests.
     */
    private fun registerTermuxActivityBroadcastReceiver() {
        if (mTermuxActivityBroadcastReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent == null || !mIsVisible) return
                when (intent.action) {
                    TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                    }
                    TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true))
                    }
                    TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        requestStoragePermission(false)
                    }
                }
            }
        }
        mTermuxActivityBroadcastReceiver = receiver
        registerReceiver(receiver, IntentFilter().apply {
            addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
            addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        })
    }

    private fun unregisterTermuxActivityBroadcastReceiver() {
        mTermuxActivityBroadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Logger.logDebug(LOG_TAG, "Error unregistering broadcast receiver: ${e.message}")
            }
        }
        mTermuxActivityBroadcastReceiver = null
    }

    /**
     * Reload the terminal styling from termux.properties and re-apply it to the
     * Compose UI. Mirrors the classic activity's reload handling.
     *
     * @param recreateActivity Whether the activity should be recreated after reloading
     */
    private fun reloadActivityStyling(recreateActivity: Boolean) {
        if (::mProperties.isInitialized) {
            mProperties.loadTermuxPropertiesFromDisk()

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode())

            // Re-apply extra keys, toolbar and font size preferences to the ViewModel
            loadExtraKeysConfig()
            mViewModel.setExtraKeysVisible(mPreferences.shouldShowTerminalToolbar())
            mViewModel.setFontSize(mPreferences.getFontSize().toFloat())
            // Reload the terminal font and ligature setting (the in-app font selector or an
            // external ~/.termux/font.ttf may have changed).
            mFontRevision++
        }

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        // To change the activity and drawer theme, activity needs to be recreated.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            recreate()
        }
    }

    /**
     * Request legacy or manage-external-storage permission and set up the storage
     * symlinks once granted. Mirrors the classic activity's request handling.
     *
     * @param isPermissionCallback Whether this call is a callback from a previous request
     */
    private fun requestStoragePermission(isPermissionCallback: Boolean) {
        Thread {
            val requestCode = if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

            if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    this@TermuxComposeActivity, requestCode, !isPermissionCallback)) {
                if (isPermissionCallback)
                    Logger.logInfoAndShowToast(this@TermuxComposeActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request))

                TermuxInstaller.setupStorageSymlinks(this@TermuxComposeActivity)
            } else {
                if (isPermissionCallback)
                    Logger.logInfoAndShowToast(this@TermuxComposeActivity, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request))
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: $requestCode")
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: $requestCode")
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    /** Build the "More" context menu of the text selection toolbar. */
    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        val currentSession = getCurrentSession()
        if (currentSession == null) return

        val terminalView = TerminalViewRegistry.activeView
        val autoFillEnabled = terminalView?.isAutoFillEnabled == true
        // The Compose selection overlay stores its text on the registry (the hidden input
        // view keeps no selection); fall back to the view field for the legacy selection.
        val storedText = TerminalViewRegistry.storedSelectedText ?: terminalView?.storedSelectedText

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(storedText))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE,
            getResources().getString(R.string.action_kill_process, currentSession.getPid()))
            .setEnabled(currentSession.isRunning())
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn())
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    /** Handle items of the "More" context menu of the text selection toolbar. */
    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = getCurrentSession()

        return when (item.itemId) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                showUrlSelection()
                true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                shareSessionTranscript()
                true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                TerminalViewRegistry.activeView?.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                TerminalViewRegistry.activeView?.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_STYLING_ID -> {
                showTerminalFontDialog()
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                setKeepScreenOn(!mIsKeepScreenOnEnabled)
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsComposeActivity::class.java))
                true
            }
            CONTEXT_MENU_REPORT_ID -> {
                reportIssueFromTranscript()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead
        // of tap for some reason
        TerminalViewRegistry.activeView?.onContextMenuClosed(menu)
        TerminalViewRegistry.setStoredSelectedText(null)
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return

        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.title_confirm_kill_process)
            .setPositiveButton(android.R.string.yes) { dialog, _ ->
                dialog.dismiss()
                session.finishIfRunning()
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            Toast.makeText(this, R.string.msg_terminal_reset, Toast.LENGTH_SHORT).show()
        }
    }

    /** Show a dialog to pick the terminal font. Mirrors the font selector in Settings. */
    private fun showTerminalFontDialog() {
        val fontOptionIds = mutableListOf("")
        val fontOptionLabels = mutableListOf(getString(R.string.font_default))
        for (entry in TerminalFontCatalog.bundledFonts) {
            fontOptionIds.add(entry.id)
            fontOptionLabels.add(getString(entry.labelRes))
        }
        if (TermuxConstants.TERMUX_FONT_FILE.isFile) {
            fontOptionIds.add(TerminalFontCatalog.CUSTOM_FONT_ID)
            fontOptionLabels.add(getString(R.string.font_custom))
        }
        val selectedIndex = fontOptionIds.indexOf(mPreferences.getTerminalFont()).coerceAtLeast(0)

        val selectedFont = arrayOf(fontOptionIds[selectedIndex])
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_font)
            .setSingleChoiceItems(fontOptionLabels.toTypedArray(), selectedIndex) { _, which ->
                selectedFont[0] = fontOptionIds[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val fontId = selectedFont[0]
                if (fontId != mPreferences.getTerminalFont()) {
                    mPreferences.setTerminalFont(fontId)
                    // Reload the Typeface in the active terminal via the composition.
                    mFontRevision++
                }
            }
            .setNeutralButton(R.string.font_import) { _, _ ->
                mFontPickerLauncher.launch(TerminalFontImporter.PICKER_MIME_TYPES)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareSessionTranscript() {
        val session = getCurrentSession() ?: return

        var transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = DataUtils.getTruncatedCommandOutput(
            transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false
        ).trim()
        ShareUtils.shareText(this, getString(R.string.title_share_transcript),
            transcriptText, getString(R.string.title_share_transcript_with))
    }

    private fun shareSelectedText() {
        // Prefer the Compose overlay's stored text (registry), then the legacy view field.
        val selectedText = TerminalViewRegistry.storedSelectedText
            ?: TerminalViewRegistry.activeView?.storedSelectedText
        if (DataUtils.isNullOrEmpty(selectedText)) return
        ShareUtils.shareText(this, getString(R.string.title_share_selected_text),
            selectedText, getString(R.string.title_share_selected_text_with))
    }

    private fun showUrlSelection() {
        val session = getCurrentSession() ?: return

        val text = ShellUtils.getTerminalSessionTranscriptText(session, true, true)
        val urlSet = TermuxUrlUtils.extractUrls(text)
        if (urlSet.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.title_select_url_none_found).show()
            return
        }

        // Latest first.
        val urls = urlSet.toTypedArray().reversedArray()

        // Click to copy url to clipboard:
        val dialog = AlertDialog.Builder(this)
            .setItems(urls) { _, which ->
                ShareUtils.copyTextToClipboard(
                    this, urls[which].toString(),
                    getString(R.string.msg_select_url_copied_to_clipboard)
                )
            }
            .setTitle(R.string.title_select_url_dialog)
            .create()

        // Long press to open URL:
        dialog.setOnShowListener {
            val listView = dialog.getListView()
            listView?.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                ShareUtils.openUrl(this, urls[position].toString())
                true
            }
        }

        dialog.show()
    }

    private fun reportIssueFromTranscript() {
        val session = getCurrentSession() ?: return

        val transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return

        MessageDialogUtils.showMessage(this, TermuxConstants.TERMUX_APP_NAME + " Report Issue",
            getString(R.string.msg_add_termux_debug_info),
            getString(com.termux.shared.R.string.action_yes),
            { _, _ -> reportIssueFromTranscript(transcriptText, true) },
            getString(com.termux.shared.R.string.action_no),
            { _, _ -> reportIssueFromTranscript(transcriptText, false) },
            null)
    }

    private fun reportIssueFromTranscript(transcriptText: String, addTermuxDebugInfo: Boolean) {
        Logger.showToast(this, getString(R.string.msg_generating_report), true)

        Thread {
            val reportString = StringBuilder()

            val title = TermuxConstants.TERMUX_APP_NAME + " Report Issue"

            reportString.append("## Transcript\n")
            reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true))
            reportString.append("\n##\n")

            if (addTermuxDebugInfo) {
                reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(
                    this@TermuxComposeActivity, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES))
            } else {
                reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(
                    this@TermuxComposeActivity, TermuxUtils.AppInfoMode.TERMUX_PACKAGE))
            }

            reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(this@TermuxComposeActivity, true))

            if (addTermuxDebugInfo) {
                val termuxDebugInfo = TermuxUtils.getTermuxDebugMarkdownString(this@TermuxComposeActivity)
                if (termuxDebugInfo != null)
                    reportString.append("\n\n").append(termuxDebugInfo)
            }

            val userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName()

            val reportInfo = ReportInfo(userActionName,
                TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title)
            reportInfo.setReportString(reportString.toString())
            reportInfo.setReportStringSuffix("\n\n" + TermuxUtils.getReportIssueMarkdownString(this@TermuxComposeActivity))
            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                Environment.getExternalStorageDirectory().toString() + "/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true))

            ReportActivity.startReportActivity(this@TermuxComposeActivity, reportInfo)
        }.start()
    }
}
