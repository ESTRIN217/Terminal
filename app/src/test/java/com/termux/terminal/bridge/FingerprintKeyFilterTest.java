package com.termux.terminal.bridge;

import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests {@link FingerprintKeyFilter}.
 */
public class FingerprintKeyFilterTest {

    private static final int KEYBOARD_SOURCE = InputDevice.SOURCE_KEYBOARD;
    private static final int TOUCHPAD_SOURCE = InputDevice.SOURCE_TOUCHPAD;
    private static final int ALPHABETIC = InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    private static final int NON_ALPHABETIC = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC;

    @Test
    public void testIgnoreFingerprintSensorUnknownKeyCode() {
        // Typical fingerprint gesture: touchpad source, non-alphabetic, KEYCODE_UNKNOWN, scanCode 4.
        Assert.assertTrue(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_UNKNOWN, TOUCHPAD_SOURCE, NON_ALPHABETIC, 4));
    }

    @Test
    public void testIgnoreFingerprintSensorZeroScanCode() {
        // Some sensor keymaps resolve a known key code with a zero scan code.
        Assert.assertTrue(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_A, TOUCHPAD_SOURCE, NON_ALPHABETIC, 0));
    }

    @Test
    public void testNotIgnoreHardwareKeyboard() {
        // A real physical keyboard key: keyboard source, alphabetic, any scan code.
        Assert.assertFalse(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_A, KEYBOARD_SOURCE, ALPHABETIC, 4));
    }

    @Test
    public void testNotIgnoreAlphabeticTouchpad() {
        // A real iPod touch / notebook touchpad with an alphabetic keymap must keep working.
        Assert.assertFalse(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_UNKNOWN, TOUCHPAD_SOURCE, ALPHABETIC, 4));
    }

    @Test
    public void testNotIgnoreKeyboardSourceNonAlphabetic() {
        // A dedicated non-alphabetic keypad (Source KEYBOARD, not TOUCHPAD) is not a sensor.
        Assert.assertFalse(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_UNKNOWN, KEYBOARD_SOURCE, NON_ALPHABETIC, 4));
    }

    @Test
    public void testNotIgnoreRegularKeyCodeAndScanCode() {
        // A normal letter key has a non-fingerprint scan code and must never be swallowed.
        Assert.assertFalse(FingerprintKeyFilter.shouldIgnore(
            KeyEvent.KEYCODE_A, TOUCHPAD_SOURCE, NON_ALPHABETIC, 30));
    }
}