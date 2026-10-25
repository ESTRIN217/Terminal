package com.termux.shared.termux.x11;

import org.junit.Assert;
import org.junit.Test;

import com.termux.shared.termux.TermuxConstants;

/**
 * Pure JUnit tests for {@link TermuxX11GuestLoaderLibrary} (spec 014, enmienda B;
 * spec 018, Enmienda A). The wrapper-script family was removed: the companion
 * only spawns from the app side, so the library is a constants holder plus the
 * RF-07 cleanup contract (device-side, not testable without Android).
 */
public class TermuxX11GuestLoaderLibraryTest {

    @Test
    public void envX11LibdirNameIsStable() {
        // The spawner and the lorie library both agree on this var name.
        Assert.assertEquals("TERMUX_X11_LIBDIR", TermuxX11GuestLoaderLibrary.ENV_X11_LIBDIR);
        Assert.assertEquals(TermuxConstants.TERMUX_X11.ENV_X11_LIBDIR,
            TermuxX11GuestLoaderLibrary.ENV_X11_LIBDIR);
    }
}
