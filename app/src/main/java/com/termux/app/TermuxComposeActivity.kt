package com.termux.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import com.termux.R
import com.termux.app.activities.FileManagerActivity
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.ComposeTerminalSessionClient
import com.termux.terminal.compose.ComposeTerminalViewClient
import com.termux.terminal.compose.DebianInstallerScreen
import com.termux.terminal.compose.ExtraKeysConfig
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TermuxMainScreen
import com.termux.terminal.compose.TermuxViewModel
import com.termux.terminal.compose.TerminalViewRegistry
import androidx.activity.enableEdgeToEdge

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
    }

    private var mTermuxService: TermuxService? = null
    private var mIsBound = false
    private var mIsVisible = false
    private var mIsActivityRecreated = false

    /** Compose state mirror of the keep-screen-on preference so the menu checkmark updates. */
    private var mIsKeepScreenOnEnabled by mutableStateOf(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Logger.logDebug(LOG_TAG, "onCreate")

        mIsActivityRecreated = savedInstanceState?.getBoolean("activity_recreated", false) ?: false

        mProperties = TermuxAppSharedProperties.getProperties()
        mPreferences = TermuxAppSharedPreferences.build(this, true)
        mViewModel = ViewModelProvider(this)[TermuxViewModel::class.java]
        mTerminalSessionClient = ComposeTerminalSessionClient(mViewModel)
        mTerminalViewClient = ComposeTerminalViewClient(mViewModel, mProperties)

        // Apply keep screen on flag if previously enabled via the more options menu
        mIsKeepScreenOnEnabled = mPreferences.shouldKeepScreenOn()
        if (mIsKeepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Load configurations
        loadExtraKeysConfig()

        val serviceIntent = Intent(this, TermuxService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, this, BIND_AUTO_CREATE)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                val palette = TerminalPalette.fromTheme()
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
                            ActivityUtils.startActivity(
                                this@TermuxComposeActivity,
                                Intent(this@TermuxComposeActivity, FileManagerActivity::class.java)
                            )
                        },
                        onOpenSettings = {
                            ActivityUtils.startActivity(
                                this@TermuxComposeActivity,
                                Intent(this@TermuxComposeActivity, SettingsActivity::class.java)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("activity_recreated", true)
    }

    override fun onResume() {
        super.onResume()
        Logger.logDebug(LOG_TAG, "onResume")
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
                addNewSession(isFailSafe, null)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Logger.logDebug(LOG_TAG, "onStop")
        mIsVisible = false

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
                val session = mViewModel.uiState.value.activeSession
                if (session != null) {
                    removeSession(session)
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
                    val intent = intent
                    val isFailSafe = intent?.getBooleanExtra(
                        TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false
                    ) ?: false
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
                    val sessionName = termuxSession.getExecutionCommand()?.shellName ?: "Session ${i + 1}"
                    mViewModel.addSession(terminalSession, sessionName)
                }
            }

            // Restore active session from saved handle (rotation recovery)
            if (mIsActivityRecreated) {
                val savedHandle = mPreferences.currentSession
                if (savedHandle != null) {
                    val savedSession = svc.getTerminalSessionForHandle(savedHandle)
                    if (savedSession != null) {
                        val state = mViewModel.uiState.value
                        val index = state.sessions.indexOfFirst { it.session == savedSession }
                        if (index >= 0) {
                            mViewModel.switchSession(index)
                        }
                    }
                }
            }
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

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            Toast.makeText(this, R.string.title_max_terminals_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val currentSession = getCurrentSession()
        val workingDirectory = currentSession?.getCwd() ?: mProperties.getDefaultWorkingDirectory()

        val termuxSession = service.createTermuxSession(
            null, null, null, workingDirectory, isFailSafe, sessionName
        ) ?: return

        val terminalSession = termuxSession.getTerminalSession()
        val name = sessionName ?: "Session ${service.getTermuxSessionsSize()}"

        mViewModel.addSession(terminalSession, name)
    }

    private fun removeSession(session: TerminalSession) {
        val service = mTermuxService ?: return

        service.removeTermuxSession(session)
        mViewModel.removeSession(session)

        if (service.getTermuxSessionsSize() == 0) {
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
}
