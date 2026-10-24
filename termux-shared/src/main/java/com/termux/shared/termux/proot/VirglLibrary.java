package com.termux.shared.termux.proot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.environment.UnixShellEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure library for the VirGL acceleration pipeline (spec 013). Nothing here
 * touches Android, disk or the clock: every decision is deterministic over
 * injected values, mirroring {@link GpuDetectionLibrary} (spec 011) and
 * {@link LlvmpipeThreadsLibrary} (spec 012).
 *
 * <p>Motives are stable English keys for later i18n mapping in
 * {@code strings.xml}; no user-visible text is produced here.</p>
 */
public final class VirglLibrary {

    private static final String LOG_TAG = "VirglLibrary";

    /**
     * Stable i18n key: the KGSL solution (spec 011) is active, so VirGL cannot
     * be stacked on top of it (RF-6 solutions are mutually exclusive).
     */
    public static final String REASON_KGSL_ACTIVE = "virgl_kgsl_active";
    /** Stable i18n key: VirGL itself is active (RF-6, symmetrical gate). */
    public static final String REASON_VIRGL_ACTIVE = "virgl_already_active";

    /**
     * MESA_GL_VERSION_OVERRIDE value injected with the virpipe driver. Placeholder
     * picked from the community-validated VirGL recipes (LinuxDroidMaster/ivon);
     * spec 013 T7 may adjust after the device validation, never silently.
     */
    public static final String GL_VERSION_OVERRIDE = "4.0";

    /**
     * File name of the vtest socket under the rootfs {@code /tmp} (the directory
     * already shared with the native side by the X11 companion, spec 014).
     * Placeholder documented in the spec's open doubts; spec 013 T7 pins the
     * final value after validating what the guest mesa {@code virpipe} accepts.
     */
    public static final String SOCKET_NAME = "virgl_test";

    /** Guest environment variable: forces the mesa virgl vtest client driver. */
    public static final String ENV_GALLIUM_DRIVER = "GALLIUM_DRIVER";
    /** Value of {@link #ENV_GALLIUM_DRIVER} selecting the VirGL client pipe. */
    public static final String GALLIUM_DRIVER_VIRPIPE = "virpipe";
    /** Guest environment variable: mesa override for the reported GL version. */
    public static final String ENV_MESA_GL_VERSION_OVERRIDE = "MESA_GL_VERSION_OVERRIDE";
    /** Guest environment variable: URI of the vtest server (modern mesa, &ge; 20.2). */
    public static final String ENV_TEST_SERVER_URI = "VIRGL_TEST_SERVER_URI";

    /** Server argument keeping the process under the app's lifecycle (RF-2/RF-4). */
    private static final String SERVER_ARG_NO_FORK = "--no-fork";
    /** Server argument accepting several concurrent guest clients (spec 013 T6). */
    private static final String SERVER_ARG_MULTI_CLIENTS = "--multi-clients";
    /** Server argument: system EGL surfaceless platform (Android has no GBM). */
    private static final String SERVER_ARG_EGL_SURFACELESS = "--use-egl-surfaceless";
    /** Server argument: Android system EGL is GLES-only. */
    private static final String SERVER_ARG_USE_GLES = "--use-gles";
    /** Server argument: explicit Unix socket path (overrides the /tmp/.virgl_test default). */
    private static final String SERVER_ARG_SOCKET_PATH = "--socket-path";

    /** Result of the mutually exclusive solution gate (RF-6). */
    public static final class SolutionGate {

        /** Whether the change may be applied now or the other way must be turned off first. */
        public enum Status {
            /** The requested target may be persisted and applied. */
            OK,
            /** The other acceleration path must be deactivated first (RF-6). */
            NEEDS_OFF
        }

        /** {@link Status} of the requested change. */
        @NonNull
        public final Status status;
        /** Solution the caller wants to persist (echoed back for clarity). */
        @Nullable
        public final GpuDetectionLibrary.Solution target;
        /** Stable i18n reason key when {@link Status#NEEDS_OFF}; null otherwise. */
        @Nullable
        public final String reason;

        private SolutionGate(@NonNull Status status, @Nullable GpuDetectionLibrary.Solution target,
                             @Nullable String reason) {
            this.status = status;
            this.target = target;
            this.reason = reason;
        }
    }

    private VirglLibrary() {
    }

    /**
     * Pure gate over the persisted solution and the requested one (RF-6):
     * through VirGL and KGSL never coexist. Transitioning between the two
     * acceleration paths always requires passing through NONE first. Turning
     * anything off is always allowed (reversibility, RF-5).
     *
     * @param current   the currently persisted solution (must not be null)
     * @param requested the solution the user wants to switch to
     * @return the gate result; {@code reason} carries the i18n key on NEEDS_OFF
     */
    @NonNull
    public static SolutionGate resolveSolutionChange(
        @NonNull GpuDetectionLibrary.Solution current,
        @NonNull GpuDetectionLibrary.Solution requested) {
        if (requested == GpuDetectionLibrary.Solution.NONE)
            return new SolutionGate(SolutionGate.Status.OK, requested, null);
        if (current == requested)
            return new SolutionGate(SolutionGate.Status.OK, requested, null);
        if (current != GpuDetectionLibrary.Solution.NONE)
            return new SolutionGate(SolutionGate.Status.NEEDS_OFF, requested,
                current == GpuDetectionLibrary.Solution.VIRGL ? REASON_VIRGL_ACTIVE : REASON_KGSL_ACTIVE);
        return new SolutionGate(SolutionGate.Status.OK, requested, null);
    }

    /**
     * Injects the VirGL guest environment when the acceleration is active and
     * the server is alive (RF-3); leaves the map byte for byte identical otherwise
     * (RF-5, NFR-3), so callers reuse it blindly.
     *
     * @param env          the already-built guest environment, mutated in place
     * @param active       whether the pipeline applies to this session
     * @param rootfsTmpDir guest {@code /tmp} as seen by the host (null/blank skips
     *                     only the server URI; the driver override still marks the way)
     */
    public static void applyVirglEnv(@NonNull Map<String, String> env, boolean active,
                                     @Nullable String rootfsTmpDir) {
        if (!active)
            return;
        env.put(ENV_GALLIUM_DRIVER, GALLIUM_DRIVER_VIRPIPE);
        env.put(ENV_MESA_GL_VERSION_OVERRIDE, GL_VERSION_OVERRIDE);
        String socketPath = guestSocketPath(rootfsTmpDir);
        if (socketPath != null)
            env.put(ENV_TEST_SERVER_URI, "unix:path=" + socketPath);
    }

    /**
     * Builds the native server environment: the vtest socket must be born in the
     * rootfs {@code /tmp} (the same directory the X11 companion already shares
     * with the guest, spec 014) so proot exposes it without extra binds.
     *
     * @param rootfsTmpDir host path of the rootfs {@code /tmp}; null/blank yields null
     * @return the environment map for the server process, or null on invalid input
     *         (the glue turns null into an Error object; never thrown)
     */
    @Nullable
    public static Map<String, String> buildServerEnv(@Nullable String rootfsTmpDir) {
        if (rootfsTmpDir == null || rootfsTmpDir.trim().isEmpty())
            return null;
        Map<String, String> env = new HashMap<>();
        env.put(UnixShellEnvironment.ENV_TMPDIR, rootfsTmpDir);
        return env;
    }

    /**
     * Immutable server arguments (RF-2, RF-4): the constant feedback of running
     * the pinned vtest sources on Android (spec 013, T6):
     * <ul>
     *   <li>{@code --no-fork}: the process must live under the app lifecycle,
     *       not fork itself away;</li>
     *   <li>{@code --multi-clients}: several guest processes (apps, X11) connect
     *       concurrently; the server trusts its Unix socket peers;</li>
     *   <li>{@code --use-egl-surfaceless}: the Android host has no DRM/GBM
     *       device; rendering goes through the system EGL surfaceless platform;</li>
     *   <li>{@code --use-gles}: the system EGL of Android is GLES-only;</li>
     *   <li>{@code --socket-path}: the vtest default name is the absolute
     *       {@code /tmp/.virgl_test}, which {@code TMPDIR} does not move, so the
     *       host path of the guest {@code /tmp} plus {@link #SOCKET_NAME} is
     *       given explicitly.</li>
     * </ul>
     *
     * @param rootfsTmpDir host path of the guest {@code /tmp}; null/blank yields null
     * @return the process arguments, or null on invalid input (the glue turns it
     *         into an Error object; never thrown)
     */
    @Nullable
    public static List<String> buildServerArgs(@Nullable String rootfsTmpDir) {
        String socketPath = guestSocketPath(rootfsTmpDir);
        if (socketPath == null)
            return null;
        List<String> args = new ArrayList<>();
        args.add(SERVER_ARG_NO_FORK);
        args.add(SERVER_ARG_MULTI_CLIENTS);
        args.add(SERVER_ARG_EGL_SURFACELESS);
        args.add(SERVER_ARG_USE_GLES);
        args.add(SERVER_ARG_SOCKET_PATH);
        args.add(socketPath);
        return args;
    }

    /**
     * Pure decision of what the native server manager must do (spec 013 T4):
     * one shared server, owned by the session lifecycle (RF-2, RF-4, NFR-6 —
     * inactive for real when no proot session needs it, not dormant).
     *
     * @param serverAlive     whether the managed server process is alive
     * @param solutionActive  whether the persisted pref is {@code VIRGL}
     * @param sessionsActive  whether any proot session is active
     * @return the action the glue must execute: {@link ServerAction#START} when
     *         the acceleration is wanted and the server is dead,
     *         {@link ServerAction#STOP} when the server is alive but nobody uses
     *         it (no sessions or solution off), {@link ServerAction#NONE} otherwise
     */
    @NonNull
    public static ServerAction decideServerAction(boolean serverAlive, boolean solutionActive,
                                                  boolean sessionsActive) {
        if (solutionActive && sessionsActive)
            return serverAlive ? ServerAction.NONE : ServerAction.START;
        if (serverAlive)
            return ServerAction.STOP;
        return ServerAction.NONE;
    }

    /**
     * Pure ready-window logic for the server launch (spec 013 T4). The glue polls
     * the vtest socket until it appears or the window is exhausted; the socket
     * appearing always wins, the timeout ending always stops the wait so the
     * launch failure is reported as an Error instead of hanging or silence.
     *
     * @param waitedMs       waited time since launch
     * @param timeoutMs      maximum window to wait for the socket
     * @param socketPresent  whether the vtest socket already exists
     * @return {@code true} only while still waiting (no socket yet, window open)
     */
    public static boolean shouldKeepWaiting(long waitedMs, long timeoutMs, boolean socketPresent) {
        if (socketPresent)
            return false;
        return waitedMs < timeoutMs;
    }

    /** Action the native server glue must execute (spec 013 T4). */
    public enum ServerAction {
        /** Launch the server process. */
        START,
        /** Terminate the running server process. */
        STOP,
        /** Keep current state (already consistent with the desired one). */
        NONE
    }

    /**
     * Pure path composition for the vtest socket; shared by the server launch
     * (stale cleanup) and the guest URI.
     *
     * @param rootfsTmpDir host path of the guest {@code /tmp}
     * @return normalized ({@code [dir]/<name>}; trailing separator collapsed),
     *         or null when the input is missing
     */
    @Nullable
    public static String guestSocketPath(@Nullable String rootfsTmpDir) {
        if (rootfsTmpDir == null || rootfsTmpDir.trim().isEmpty())
            return null;
        String dir = rootfsTmpDir.trim();
        while (dir.endsWith("/"))
            dir = dir.substring(0, dir.length() - 1);
        return dir + "/" + SOCKET_NAME;
    }
}
