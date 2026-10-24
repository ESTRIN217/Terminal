package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.proot.GpuDetectionLibrary;
import com.termux.shared.termux.proot.VirglLibrary;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle glue for the native VirGL server (spec 013): owns one shared server
 * process whose existence follows the session lifecycle, exactly the pattern of
 * {@link com.termux.shared.termux.proot.ProcStatOverlaySampler} (spec 010) —
 * session bookkeeping happens in {@link TermuxService}, all decisions come from
 * the pure {@link VirglLibrary#decideServerAction}, and every failure surfaces
 * as an {@link Error} plus logs, never a throw and never silence (RF-2, RF-9).
 *
 * <p>The spawned process is {@code virgl_test_server --no-fork} (pure
 * {@link VirglLibrary#buildServerArgs}), so its lifetime is owned by this
 * manager instead of a runaway daemon. Environment: {@code TMPDIR} points to
 * the rootfs {@code /tmp} (pure {@link VirglLibrary#buildServerEnv}), the same
 * directory the X11 companion (spec 014) already shares with the guest, so the
 * vtest socket needs no extra proot binds.</p>
 */
public final class VirglServerManager {

    private static final String LOG_TAG = "VirglServerManager";

    /** Window to wait for the vtest socket after launch (pure {@link VirglLibrary#shouldKeepWaiting}). */
    private static final long READY_TIMEOUT_MS = 3000;
    /** Poll period for the ready check. */
    private static final long READY_POLL_MS = 100;

    /** The one shared server process; null when currently stopped. */
    private static volatile Process mServerProcess;

    private VirglServerManager() {
    }

    /**
     * Applies the desired server state for the given number of active proot
     * sessions (RF-2, RF-4): start it when the acceleration is wanted and no
     * server exists; stop it when there is no longer any user (session closed
     * or solution off). Idempotent — safe to call on every session change.
     *
     * @param context             Android context for prefs/paths; may be {@code null}.
     * @param activeProotSessions count of currently active proot sessions
     * @return Returns {@code null} when the state matches; an {@link Error} when
     *          the server failed to start (the pref stays untouched, RF-2).
     */
    @Nullable
    public static synchronized Error applySessionCount(@Nullable Context context, int activeProotSessions) {
        boolean solutionActive = isVirglSolution(context);
        VirglLibrary.ServerAction action =
            VirglLibrary.decideServerAction(isAlive(), solutionActive, activeProotSessions > 0);
        switch (action) {
            case START:
                return start(context);
            case STOP:
                stop("no active proot session requests the VirGL server");
                return null;
            case NONE:
            default:
                return null;
        }
    }

    /**
     * Turns the server on right after the user activates the pipeline in
     * settings (RF-2(b)); intended to be called after the pref is persisted.
     *
     * @param context Android context; may be {@code null}.
     * @return Returns {@code null} on success (server alive), or an {@link Error}.
     */
    @Nullable
    public static synchronized Error ensureRunning(@Nullable Context context) {
        return start(context);
    }

    /**
     * Stops the server after the user deactivates the pipeline (RF-5). Success
     * even when the server was not running; failure paths are logged.
     *
     * @param context Android context; may be {@code null}.
     */
    public static synchronized void stopNow(@Nullable Context context) {
        stop("user deactivated VirGL");
    }

    /**
     * Whether the user-visible 3D acceleration pipeline considers the server
     * alive (RF-2(c) panel uses this for the honest state). Sufficient signal,
     * because a dead process would have been reaped/restarted by the session
     * hooks or by {@code getEnvironment}'s socket check.
     */
    public static boolean isAlive() {
        Process process = mServerProcess;
        return process != null && process.isAlive();
    }

    /** Starts the server: binary from assets, stale socket cleaned, env set, ready window polled. */
    @Nullable
    private static synchronized Error start(@Nullable Context context) {
        if (isAlive())
            return null;

        Error installError = TermuxInstaller.installVirglIfNeeded(context);
        if (installError != null) {
            Logger.logErrorExtended(LOG_TAG, "VirGL server binary unavailable\n" + installError.toString());
            return new Error("Failed to install the VirGL server binary\n" + installError.toString());
        }

        String tmpDir = TermuxConstants.DEBIAN_ROOTFS_TMP_DIR_PATH;
        String socketPath = VirglLibrary.guestSocketPath(tmpDir);
        Error staleError = removeStaleSocket(socketPath);
        if (staleError != null)
            Logger.logWarn(LOG_TAG, "Stale socket cleanup failed (continuing)\n" + staleError.toString());

        Map<String, String> serverEnv = VirglLibrary.buildServerEnv(tmpDir);
        if (serverEnv == null)
            return new Error("Invalid rootfs /tmp path for the VirGL server");

        List<String> args = VirglLibrary.buildServerArgs(tmpDir);
        if (args == null)
            return new Error("Invalid rootfs /tmp path for the VirGL server arguments");
        java.util.List<String> command = new java.util.ArrayList<>(args.size() + 1);
        command.add(TermuxConstants.VIRGL_BIN_PATH);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(serverEnv);
        builder.redirectErrorStream(true);
        Logger.logInfo(LOG_TAG, "Starting VirGL server \"" + TermuxConstants.VIRGL_BIN_PATH + "\" (vtest socket: "
            + (socketPath != null ? socketPath : "unresolved") + ")");

        Process process;
        try {
            process = builder.start();
        } catch (Exception e) {
            return new Error("Failed to start the VirGL server process", e);
        }
        mServerProcess = process;
        discardOutput(process);

        for (long waited = 0; VirglLibrary.shouldKeepWaiting(waited, READY_TIMEOUT_MS,
            socketPath != null && new File(socketPath).exists()); waited += READY_POLL_MS) {
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!isAlive()) {
            int exitValue;
            try {
                exitValue = process.exitValue();
            } catch (IllegalThreadStateException e) {
                exitValue = -1;
            }
            mServerProcess = null;
            Error error = new Error("The VirGL server exited during startup (exit code " + exitValue + ")");
            Logger.logErrorExtended(LOG_TAG, error.toString() + "\nsocket: " + socketPath);
            return error;
        }

        if (socketPath == null || !new File(socketPath).exists()) {
            stop("server alive but the vtest socket never appeared");
            Error error = new Error("The VirGL server did not create its vtest socket within "
                + TimeUnit.MILLISECONDS.toSeconds(READY_TIMEOUT_MS) + " s (socket: "
                + (socketPath != null ? socketPath : "unresolved") + ")");
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return error;
        }

        Logger.logInfo(LOG_TAG, "VirGL server ready (socket present).");
        return null;
    }

    /** Stops and reaps the process plus cleans the socket file; never throws. */
    private static synchronized void stop(@Nullable String reason) {
        Process process = mServerProcess;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS))
                    process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            Logger.logInfo(LOG_TAG, "VirGL server stopped (" + reason + ").");
        }
        mServerProcess = null;
        Error cleanupError = removeStaleSocket(VirglLibrary.guestSocketPath(TermuxConstants.DEBIAN_ROOTFS_TMP_DIR_PATH));
        if (cleanupError != null)
            Logger.logWarn(LOG_TAG, "Socket cleanup failed at stop\n" + cleanupError.toString());
    }

    /**
     * Removes a stale vtest socket left by a crashed server (spec 013 edge
     * case) so the new server can bind again; missing file is not an error.
     */
    @Nullable
    private static Error removeStaleSocket(@Nullable String socketPath) {
        if (socketPath == null)
            return new Error("VirGL socket path could not be resolved");
        File file = new File(socketPath);
        try {
            if (file.exists() && !file.delete())
                return new Error("Failed to delete the stale VirGL socket \"" + socketPath + "\"");
            return null;
        } catch (Exception e) {
            return new Error("Failed to delete the stale VirGL socket \"" + socketPath + "\"", e);
        }
    }

    /** Reads the persisted pref with the inert-default policy (any failure → not active). */
    private static boolean isVirglSolution(@Nullable Context context) {
        if (context == null)
            return false;
        try {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
            return preferences != null
                && preferences.getGpuSolution() == GpuDetectionLibrary.Solution.VIRGL;
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to read gpu_solution preference (server stays stopped)\n" + e.toString());
            return false;
        }
    }

    /**
     * Drains the server stdout/stderr in a detached thread so the never-forked
     * process cannot block on a full pipe; output is debug-logged only.
     */
    private static void discardOutput(@NonNull Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader reader1 = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader1.readLine()) != null)
                    Logger.logDebug(LOG_TAG, "virgl_test_server: " + line);
            } catch (Exception ignored) {
                // the stream ends when the process dies; nothing to report here
            }
        }, "virgl-server-output");
        reader.setDaemon(true);
        reader.start();
    }
}
