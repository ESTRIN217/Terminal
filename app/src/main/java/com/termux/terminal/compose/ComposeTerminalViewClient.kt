package com.termux.terminal.compose

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
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
    private val mOnRemoveSession: (TerminalSession) -> Unit
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

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
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
        val s = session ?: return handleVirtualKeys(keyCode, e, true)
        if (keyCode == KeyEvent.KEYCODE_ENTER && !s.isRunning()) {
            // Enter on a finished session removes it, instead of writing to the
            // dead process (mirrors TermuxTerminalViewClient.onKeyDown()).
            mOnRemoveSession(s)
            return true
        }
        return handleVirtualKeys(keyCode, e, true)
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        return handleVirtualKeys(keyCode, e, false)
    }

    override fun onLongPress(event: MotionEvent?): Boolean {
        // Let TerminalView start its default text selection mode
        return false
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
        val s = session ?: return false
        if (ctrlDown && codePoint == 106 /* Ctrl+j or \n */ && !s.isRunning()) {
            // Mirrors TermuxTerminalViewClient.onCodePoint(): remove the finished session.
            mOnRemoveSession(s)
            return true
        }
        // Let TerminalView write the code point to the session; Ctrl modifiers are
        // transformed there (inputCodePoint), matching the default terminal behavior.
        return false
    }

    override fun onEmulatorSet() {
        TerminalViewRegistry.activeView?.setTerminalCursorBlinkerState(true, true)
        // The view palette could not be applied when the emulator did not exist yet.
        TerminalViewRegistry.reapplyPendingPalette()
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
