package com.termux.terminal.compose

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.bridge.FingerprintKeyFilter
import com.termux.view.TerminalViewClient

/**
 * [TerminalViewClient] implementation for the Compose UI.
 *
 * Handles the virtual modifier keys (Volume Down as Ctrl, Volume Up as Fn), pinch to zoom
 * font scaling, tap to show the soft keyboard and forwards configuration queries from
 * {@link com.termux.view.TerminalView} to the app properties. Everything else is left to the
 * default handling of {@link com.termux.view.TerminalView}.
 */
class ComposeTerminalViewClient(
    private val mViewModel: TermuxViewModel,
    private val mProperties: TermuxAppSharedProperties,
    private val mOnRemoveSession: (TerminalSession) -> Unit,
    private val mOnShowMoreMenu: () -> Unit
) : TerminalViewClient {

    companion object {
        private const val LOG_TAG = "ComposeTerminalViewClient"
    }

    /** Whether the Volume Down key is currently held (virtual Ctrl). */
    private var mVirtualControlKeyDown = false

    /** Whether the Volume Up key is currently held (virtual Fn). */
    private var mVirtualFnKeyDown = false

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val currentSize = mViewModel.uiState.value.fontSize
            val newSize = if (scale > 1.0f) currentSize + 2f else currentSize - 2f
            mViewModel.setFontSize(newSize.coerceIn(8f, 32f))
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        showSoftKeyboard()
    }

    /** Whether the last BACK key-down consumed a native-pane selection dismiss. */
    private var mSelectionBackKeyUp = false

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        // While the native pane has a text selection, force the escape-mapping branch in
        // TerminalView.onKeyPreIme so BACK reaches onKeyDown, which dismisses the selection
        // and consumes the event without writing ESC (legacy onKeyPreIme checks
        // isSelectingText() on the view itself, which is always false for the hidden host).
        if (TerminalViewRegistry.isActivePaneSelecting) return true
        return mProperties.isBackKeyTheEscapeKey()
    }

    override fun shouldEnforceCharBasedInput(): Boolean {
        return mProperties.isEnforcingCharBasedInput()
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return mProperties.isUsingCtrlSpaceWorkaround()
    }

    override fun isTerminalViewSelected(): Boolean {
        return true
    }

    override fun copyModeChanged(copyMode: Boolean) {
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (e != null && isFingerprintSensorEvent(keyCode, e)) {
            Logger.logDebug(LOG_TAG, "Ignoring fingerprint sensor key event: " + e)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && TerminalViewRegistry.isActivePaneSelecting) {
            // Legacy TerminalView.onKeyDown stops an active text selection on any key down
            // before the escape branch; consume BACK so ESC is never written while selecting.
            TerminalViewRegistry.dismissActivePaneSelection()
            mSelectionBackKeyUp = true
            return true
        }
        val s = session ?: return handleVirtualKeys(keyCode, e, true)
        if (keyCode == KeyEvent.KEYCODE_ENTER && !s.isRunning()) {
            // Enter on a finished session removes it, instead of writing to the
            // dead process.
            mOnRemoveSession(s)
            return true
        }
        return handleVirtualKeys(keyCode, e, true)
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && mSelectionBackKeyUp) {
            // Pair for the selection-dismiss key-down: consume the UP too so it does not
            // fall through to the activity as a real back press.
            mSelectionBackKeyUp = false
            return true
        }
        return handleVirtualKeys(keyCode, e, false)
    }

    override fun onLongPress(event: MotionEvent?): Boolean {
        // Let TerminalView start its default text selection mode
        return false
    }

    override fun onShowMoreMenu() {
        // Legacy selection-toolbar MORE / mouse right-click: open the Compose more-menu sheet.
        mOnShowMoreMenu()
    }

    override fun readControlKey(): Boolean {
        return isExtraKeyModifierActive("CTRL") || mVirtualControlKeyDown
    }

    override fun readAltKey(): Boolean {
        return isExtraKeyModifierActive("ALT")
    }

    override fun readShiftKey(): Boolean {
        return isExtraKeyModifierActive("SHIFT")
    }

    override fun readFnKey(): Boolean {
        return isExtraKeyModifierActive("FN") || mVirtualFnKeyDown
    }

    /**
     * Whether a sticky modifier key from the extra keys bar is currently active.
     *
     * The bar holds its sticky toggle state in the ViewModel so that keys typed on
     * the system keyboard (via [TerminalView]'s `inputCodePoint`) get the modifier
     * applied, mirroring classic Termux behavior.
     *
     * @param modifier The modifier key name (e.g., "CTRL")
     * @return True if the modifier is active on the extra keys bar
     */
    private fun isExtraKeyModifierActive(modifier: String): Boolean {
        return modifier in mViewModel.uiState.value.extraKeysModifiers
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // Legacy TerminalView.sendTextToTerminal() calls stopTextSelectionMode() before
        // writing IME text; soft-IME commitText never hits the OnKeyListener, so clear the
        // native pane selection here (also reached for hardware-produced code points, where
        // a second clear is a no-op) and re-show the cursor like setCursorBlinkState(true).
        TerminalViewRegistry.dismissActivePaneSelection()
        TerminalViewRegistry.fireNativeTextInput()
        val s = session ?: return false
        if (ctrlDown && codePoint == 106 /* Ctrl+j or \n */ && !s.isRunning()) {
            // Remove a finished session on Ctrl+j.
            mOnRemoveSession(s)
            return true
        }
        // Let TerminalView write the code point to the session; Ctrl modifiers are
        // transformed there (inputCodePoint), matching the default terminal behavior.
        return false
    }

    override fun onEmulatorSet() {
        // The emulator of any composed view (focused or secondary pane) may be created after
        // layout; re-apply the pending palette and enable the cursor blinker on each of them.
        // The blinker rate must be set first: setTerminalCursorBlinkerState() no-ops while the
        // rate stays at the default 0, so the legacy path never blinked until now.
        val blinkRate = mProperties.terminalCursorBlinkRate
        TerminalViewRegistry.forComposedViews { view ->
            view.setTerminalCursorBlinkerRate(blinkRate)
            view.setTerminalCursorBlinkerState(true, true)
            TerminalViewRegistry.reapplyPendingPalette(view)
        }
    }

    override fun logError(tag: String?, message: String?) {
        Logger.logError(tag ?: LOG_TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Logger.logWarn(tag ?: LOG_TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Logger.logInfo(tag ?: LOG_TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Logger.logDebug(tag ?: LOG_TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Logger.logVerbose(tag ?: LOG_TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Logger.logStackTraceWithMessage(tag ?: LOG_TAG, message ?: "", e ?: return)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Logger.logStackTrace(tag ?: LOG_TAG, e ?: return)
    }

    /**
     * Whether the key event originates from the fingerprint sensor gesture area.
     *
     * @param keyCode The event key code.
     * @param event The key event.
     * @return {@code true} if the event should be swallowed.
     */
    private fun isFingerprintSensorEvent(keyCode: Int, event: KeyEvent): Boolean {
        val keyboardType = event.device?.keyboardType ?: InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC
        return FingerprintKeyFilter.shouldIgnore(keyCode, event.source, keyboardType, event.scanCode)
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent?, down: Boolean): Boolean {
        if (event == null || mProperties.areVirtualVolumeKeysDisabled()) {
            return false
        }

        val inputDevice: InputDevice? = event.device
        if (inputDevice != null && inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard
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

    private fun showSoftKeyboard() {
        val view = TerminalViewRegistry.activeView ?: return
        KeyboardUtils.showSoftKeyboard(view.context, view)
    }
}
