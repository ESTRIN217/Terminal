package com.termux.shared.termux.x11;

import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Companion-side environment variable names shared between the app-side spawn
 * ([X11CompanionSpawner]) and the lorie library. The guest wrapper script and
 * the {@code TERMUX_X11_APK} classpath injection (spec 014, enmienda A) are
 * retired: the companion always starts from the app side (spec 014,
 * enmienda B; spec 018, Enmienda A, RF-07/RF-08).
 */
public final class TermuxX11GuestLoaderLibrary {

    private static final String LOG_TAG = "TermuxX11GuestLoaderLibrary";

    private TermuxX11GuestLoaderLibrary() {
    }

    /** Spawner environment variable holding the app's extracted native library dir (legacy packaging). */
    public static final String ENV_X11_LIBDIR = TermuxConstants.TERMUX_X11.ENV_X11_LIBDIR;

    /**
     * Delete the guest wrapper script left by older builds at
     * {@link TermuxConstants.TERMUX_X11#X11_GUEST_WRAPPER_HOST_PATH}
     * (spec 018, Enmienda A, RF-07). Idempotent: a no-op when the file is
     * absent. Errors are returned, never thrown.
     *
     * @return {@code null} on success (including no-op), otherwise the {@link Error}
     */
    @Nullable
    public static Error removeGuestWrapperIfPresent() {
        String hostPath = TermuxConstants.TERMUX_X11.X11_GUEST_WRAPPER_HOST_PATH;
        if (!new File(hostPath).isFile())
            return null;

        Error removeError = FileUtils.deleteFile("termux-x11 guest wrapper", hostPath, true);
        if (removeError != null) {
            Logger.logErrorExtended(LOG_TAG, "Failed to remove guest wrapper\n" + removeError);
            return removeError;
        }

        Logger.logDebug(LOG_TAG, "termux-x11 guest wrapper removed from \"" + hostPath + "\"");
        return null;
    }

}
