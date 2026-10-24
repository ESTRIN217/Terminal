package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.proot.GpuDetectionLibrary;
import com.termux.shared.termux.proot.LlvmpipeThreadsLibrary;
import com.termux.shared.termux.proot.VirglLibrary;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.x11.TermuxX11GuestLoaderLibrary;

import java.nio.charset.Charset;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Guest environment for the Debian rootfs executed via proot (Fase 4).
 *
 * <p>Unlike {@link TermuxShellEnvironment}, this environment is independent of
 * {@code $PREFIX}: the guest is FHS ({@code /bin}, {@code /usr}, ...) and all
 * paths are package-derived from {@link TermuxConstants}, so the fork stays
 * renamable. {@code LD_PRELOAD}/{@code LD_LIBRARY_PATH} are never set since
 * {@code termux-exec} conflicts with proot.</p>
 */
public class ProotShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "ProotShellEnvironment";

    /** Environment variable for the bundled proot temp dir (extracted loader). */
    public static final String ENV_PROOT_TMP_DIR = "PROOT_TMP_DIR";

    /** Extra environment variable names set for the guest. */
    public static final String ENV_USER = "USER";
    public static final String ENV_LOGNAME = "LOGNAME";
    public static final String ENV_SHELL = "SHELL";
    public static final String ENV_LC_ALL = "LC_ALL";
    public static final String ENV_LANGUAGE = "LANGUAGE";
    public static final String ENV_DEBIAN_FRONTEND = "DEBIAN_FRONTEND";
    public static final String ENV_LD_PRELOAD = "LD_PRELOAD";
    /** Guest IANA timezone (from the host device settings). */
    public static final String ENV_TZ = "TZ";

    /**
     * Mesa loader driver override (spec 011, RF-4). While {@code gpu_solution=ADRENO_KGSL}
     * is persisted, this variable forces Mesa's KGSL gallium driver inside the guest so
     * the Adreno GPU is used instead of llvmpipe. It only ever reaches proot guest
     * processes (see {@link ProotShellEnvironment#getEnvironment}); the Termux
     * ({@code $PREFIX}) environment is untouched (NFR-3).
     */
    public static final String ENV_MESA_LOADER_DRIVER_OVERRIDE = "MESA_LOADER_DRIVER_OVERRIDE";

    /** Value for {@link #ENV_MESA_LOADER_DRIVER_OVERRIDE}: Mesa's KGSL gallium driver. */
    public static final String GUEST_MESA_KGSL_DRIVER = "kgsl";

    /** Guest user and home (proot {@code -0} maps to root). */
    public static final String GUEST_USER = "root";
    public static final String GUEST_HOME = "/root";
    public static final String GUEST_SHELL = "/bin/bash";

    /** Guest locale (always present: C.utf8 ships in the minimal rootfs). */
    public static final String GUEST_LANG = "C.UTF-8";
    /** Non-interactive apt/debconf: the minimal rootfs has no Dialog/Readline frontend. */
    public static final String GUEST_DEBIAN_FRONTEND = "noninteractive";

    /** Guest {@code PATH} (pure Debian FHS, no host paths). */
    public static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Guest directory of the IANA zoneinfo database (provided by the tzdata package). */
    public static final String GUEST_ZONEINFO_DIR = "/usr/share/zoneinfo";

    /**
     * Translate an Android {@code hostPath} to the equivalent Debian guest path.
     *
     * <p>Host paths under the rootfs ({@code files/debian/...}) map directly to
     * guest paths; shared-storage paths map through the proot binds
     * {@code /sdcard:/root/sdcard} and {@code /storage:/root/storage}. Any other
     * path (e.g. {@code files/home}) has no guest equivalent and falls back to the
     * guest home {@code /root} so it can still be used as a proot working directory.</p>
     *
     * @param hostPath The Android (host) path to translate.
     * @return The corresponding guest path, or {@value #GUEST_HOME} when unmappable.
     */
    @NonNull
    public static String hostPathToGuestPath(@NonNull String hostPath) {
        if (hostPath.startsWith(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH)) {
            String relative = hostPath.substring(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH.length());
            while (relative.startsWith("/"))
                relative = relative.substring(1);
            return relative.isEmpty() ? "/" : "/" + relative;
        }
        if (hostPath.startsWith("/sdcard")) {
            String relative = hostPath.substring("/sdcard".length());
            return GUEST_HOME + "/sdcard" + relative;
        }
        if (hostPath.startsWith("/storage")) {
            String relative = hostPath.substring("/storage".length());
            return GUEST_HOME + "/storage" + relative;
        }
        return GUEST_HOME;
    }

    /**
     * Whether {@code hostPath} has a real guest equivalent (rootfs, {@code /sdcard} or
     * {@code /storage} bind).
     *
     * <p>{@link #hostPathToGuestPath(String)} silently falls back to {@link #GUEST_HOME}
     * for unmappable paths (fine as a proot working directory, wrong as a file to open in a
     * guest editor). Callers that need a true mapping must check this first.</p>
     *
     * @param hostPath The Android (host) path to test.
     * @return Returns {@code true} when the path maps into the guest filesystem.
     */
    public static boolean isHostPathMappable(@NonNull String hostPath) {
        return hasPathPrefix(hostPath, TermuxConstants.DEBIAN_ROOTFS_DIR_PATH)
            || hasPathPrefix(hostPath, "/sdcard")
            || hasPathPrefix(hostPath, "/storage");
    }

    /**
     * Whether {@code path} equals {@code prefix} or starts with {@code prefix + "/"}.
     * Avoids false positives like {@code /sdcardfoo} for {@code /sdcard}.
     */
    private static boolean hasPathPrefix(@NonNull String path, @NonNull String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * Host directory the native pty process chdirs into before exec'ing proot.
     *
     * <p>proot re-establishes the real guest working directory via {@code -w}, so
     * the native {@code chdir} only needs to succeed silently. The guest home host
     * directory always exists after installation and is owned by the app, avoiding
     * the noisy {@code chdir(...): Permission denied} on proot bind destinations.</p>
     *
     * @return The guest home directory on the host filesystem.
     */
    @NonNull
    public static String getNativeWorkingDirectoryPath() {
        return TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH;
    }

    /**
     * Testable core of the guest GPU environment injection (spec 011, RF-4/NFR-3).
     *
     * <p>Pure: while {@code gpuSolutionActive}, stores {@code MESA_LOADER_DRIVER_OVERRIDE=kgsl}
     * in {@code map} (overwriting any stale value); otherwise {@code map} is left
     * byte-for-byte identical to the pre-spec guest environment.</p>
     *
     * @param map               The guest environment map being built, modified in place.
     * @param gpuSolutionActive Whether the persisted {@code gpu_solution} pref is {@code ADRENO_KGSL}.
     * @return Returns the same {@code map} for convenience.
     */
    static Map<String, String> applyGpuEnv(@NonNull Map<String, String> map, boolean gpuSolutionActive) {
        if (gpuSolutionActive)
            map.put(ENV_MESA_LOADER_DRIVER_OVERRIDE, GUEST_MESA_KGSL_DRIVER);
        return map;
    }

    /**
     * Whether the persisted {@code gpu_solution} app preference is {@code ADRENO_KGSL}
     * (spec 011, RF-4). Any failure to read the preference keeps the environment inert
     * (identical to the pre-spec guest env, NFR-3).
     *
     * @param currentPackageContext The {@link Context} for reading the app preferences; may be {@code null}.
     * @return Returns {@code true} only when {@code gpu_solution=ADRENO_KGSL} is readable.
     */
    static boolean isGpuSolutionActive(@Nullable Context currentPackageContext) {
        if (currentPackageContext == null)
            return false;
        try {
            TermuxAppSharedPreferences preferences =
                TermuxAppSharedPreferences.build(currentPackageContext);
            return preferences != null
                && preferences.getGpuSolution() == GpuDetectionLibrary.Solution.ADRENO_KGSL;
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to read gpu_solution preference (env stays inert)\n" + e.toString());
            return false;
        }
    }

    /**
     * Apply the llvmpipe multithreading environment variables (spec 012, RF-1, RF-2, RF-4).
     *
     * <p>When {@code enabled} is {@code true} and {@code gpuSolutionActive} is {@code false},
     * {@link LlvmpipeThreadsLibrary#computeThreads(int, int)} is used to compute the clamped
     * thread count and {@link LlvmpipeThreadsLibrary#llvmpipeEnvEntries(int)} adds
     * {@code LP_NUM_THREADS} and {@code GALLIUM_NUM_THREADS} to the map.
     * When disabled or when GPU is accelerated, the map is left untouched.</p>
     *
     * @param map               The guest environment map being built, modified in place.
     * @param enabled           Whether the llvmpipe multithreading preference is enabled.
     * @param gpuSolutionActive Whether the GPU is accelerated (spec 011).
     * @param coresRaw          Host core count from {@code Runtime.availableProcessors()}.
     * @param prefThreadsCount  Configured thread count preference N.
     * @return Returns the same {@code map} for convenience.
     */
    static Map<String, String> applyLlvmpipeEnv(@NonNull Map<String, String> map,
                                                boolean enabled,
                                                boolean gpuSolutionActive,
                                                int coresRaw,
                                                int prefThreadsCount) {
        if (!LlvmpipeThreadsLibrary.shouldInject(enabled, gpuSolutionActive))
            return map;

        int threads = LlvmpipeThreadsLibrary.computeThreads(coresRaw, prefThreadsCount);
        map.putAll(LlvmpipeThreadsLibrary.llvmpipeEnvEntries(threads));
        return map;
    }

    /**
     * Testable core of the VirGL guest environment injection (spec 013, RF-3/RF-5/NFR-3).
     *
     * <p>Pure: while {@code prefActive} and the server socket is {@code socketPresent},
     * stores the VirGL guest variables (gallium driver override, GL version and vtest
     * server URI) via {@link VirglLibrary}; otherwise {@code map} is left byte-for-byte
     * identical to the pre-spec guest environment.</p>
     *
     * @param map            The guest environment map being built, modified in place.
     * @param prefActive     Whether the persisted {@code gpu_solution} pref is {@code VIRGL}.
     * @param socketPresent  Whether the vtest socket of the native server exists on the host.
     * @param rootfsTmpDir   Host path of the guest {@code /tmp}; null skips the URI only.
     */
    public static void applyVirglSessionEnv(@NonNull Map<String, String> map, boolean prefActive,
                                            boolean socketPresent, @Nullable String rootfsTmpDir) {
        VirglLibrary.applyVirglEnv(map, prefActive && socketPresent, rootfsTmpDir);
    }

    /**
     * Whether the persisted {@code gpu_solution} app preference is {@code VIRGL}
     * (spec 013, RF-2). Same failure policy as {@link #isGpuSolutionActive}: any
     * read failure keeps the environment inert (NFR-3).
     *
     * @param currentPackageContext The {@link Context} for reading the app preferences; may be {@code null}.
     * @return Returns {@code true} only when {@code gpu_solution=VIRGL} is readable.
     */
    static boolean isVirglSolutionActive(@Nullable Context currentPackageContext) {
        if (currentPackageContext == null)
            return false;
        try {
            TermuxAppSharedPreferences preferences =
                TermuxAppSharedPreferences.build(currentPackageContext);
            return preferences != null
                && preferences.getGpuSolution() == GpuDetectionLibrary.Solution.VIRGL;
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to read gpu_solution=VIRGL preference (env stays inert)\n" + e.toString());
            return false;
        }
    }

    /**
     * Whether the VirGL vtest socket is visible on the host (spec 013, RF-3
     * "server alive"). The socket lives in the rootfs {@code /tmp} — the same
     * directory the X11 companion already shares with the guest (spec 014) —
     * so its existence doubles as the server-liveness signal without any
     * process inspection from this layer. Never throws; failures log and keep
     * the environment inert (RF-9).
     *
     * @return Returns {@code true} only when the server socket file exists.
     */
    static boolean isVirglSocketPresent() {
        try {
            String socketPath = VirglLibrary.guestSocketPath(TermuxConstants.DEBIAN_ROOTFS_TMP_DIR_PATH);
            if (socketPath == null)
                return false;
            return new File(socketPath).exists();
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to check for the VirGL vtest socket (env stays inert)\n" + e.toString());
            return false;
        }
    }

    /**
     * Whether the multithreaded llvmpipe env applies (spec 012/013, pure). The
     * threads variables only speak to the CPU rasterizer they configure: while
     * any GPU path (KGSL or VirGL) is active, they stay out of the map.
     *
     * @param llvmpipeEnabled Whether the llvmpipe multithreading pref is enabled.
     * @param gpuActive       Whether the KGSL path is active.
     * @param virglActive     Whether the VirGL path is active.
     * @return Returns {@code true} only when enabled and no GPU path is active.
     */
    static boolean shouldInjectLlvmpipeThreads(boolean llvmpipeEnabled, boolean gpuActive, boolean virglActive) {
        return LlvmpipeThreadsLibrary.shouldInject(llvmpipeEnabled, gpuActive || virglActive);
    }

    /**
     * Whether the llvmpipe multithreading lever is enabled in app preferences (spec 012, RF-1, RF-2).
     * Missing preference defaults to {@code true}; null context or error keeps the env inert (false).
     *
     * @param currentPackageContext The {@link Context} for reading preferences; may be {@code null}.
     * @return Returns {@code true} if llvmpipe multithreading should be active.
     */
    static boolean isLlvmpipeThreadsEnabled(@Nullable Context currentPackageContext) {
        if (currentPackageContext == null)
            return false;
        try {
            TermuxAppSharedPreferences preferences =
                TermuxAppSharedPreferences.build(currentPackageContext);
            return preferences != null && preferences.isLlvmpipeThreadsEnabled();
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to read llvmpipe_threads_enabled preference (env stays inert)\n" + e.toString());
            return false;
        }
    }

    /**
     * Get the configured llvmpipe thread count from app preferences (spec 012, RF-1).
     *
     * @param currentPackageContext The {@link Context} for reading preferences; may be {@code null}.
     * @return Returns the configured count N (default 8).
     */
    static int getLlvmpipeThreadsCount(@Nullable Context currentPackageContext) {
        if (currentPackageContext == null)
            return LlvmpipeThreadsLibrary.DEFAULT_THREADS;
        try {
            TermuxAppSharedPreferences preferences =
                TermuxAppSharedPreferences.build(currentPackageContext);
            return preferences != null ? preferences.getLlvmpipeThreadsCount() : LlvmpipeThreadsLibrary.DEFAULT_THREADS;
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to read llvmpipe_threads_count preference\n" + e.toString());
            return LlvmpipeThreadsLibrary.DEFAULT_THREADS;
        }
    }

    public ProotShellEnvironment() {
        super();
    }

    /**
     * Write the guest environment to {@code files/debian.env} for debugging.
     * Unlike {@link TermuxShellEnvironment#writeEnvironmentToFile(Context)}, this does
     * not touch {@code $PREFIX}.
     *
     * @param currentPackageContext The {@link Context} for operations.
     */
    public static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new ProotShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        Error error = FileUtils.writeTextToFile("debian.env",
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/debian.env",
            Charset.defaultCharset(), environmentString, false);
        if (error != null)
            Logger.logErrorExtended(LOG_TAG, error.toString());
    }

    /**
     * Whether the link(2)-emulation shim is present in the on-device rootfs.
     *
     * @return Returns {@code true} if the shim file exists on the host.
     */
    public static boolean isLinkfixInstalledOnHost() {
        return new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH
            + TermuxConstants.LINKFIX_GUEST_SO_PATH).isFile();
    }

    /**
     * Whether the on-device rootfs tzdata contains a guest zoneinfo entry for
     * {@code timeZoneId}.
     *
     * <p>Probing the entry keeps {@code TZ} away from zones the guest cannot
     * resolve: glibc would silently fall back to UTC, and POSIX-style IDs like
     * {@code GMT+04:00} (which glibc parses with an inverted offset sign) are
     * rejected up front since they never exist as zoneinfo files.</p>
     *
     * @param timeZoneId The IANA zone id to look for (e.g. {@code America/Caracas}).
     * @return Returns {@code true} if the zonefile exists in the guest rootfs.
     */
    public static boolean isGuestTimeZoneAvailable(@NonNull String timeZoneId) {
        return isGuestTimeZoneAvailable(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH, timeZoneId);
    }

    /**
     * Testable core of {@link #isGuestTimeZoneAvailable(String)} over an explicit
     * rootfs directory path.
     *
     * @param rootfsDirPath The host path of the guest rootfs directory.
     * @param timeZoneId    The IANA zone id to look for.
     * @return Returns {@code true} if {@code <rootfsDirPath>/usr/share/zoneinfo/<timeZoneId>}
     * is an existing regular file.
     */
    static boolean isGuestTimeZoneAvailable(@Nullable String rootfsDirPath, @Nullable String timeZoneId) {
        if (rootfsDirPath == null || timeZoneId == null
            || timeZoneId.isEmpty() || timeZoneId.contains(".."))
            return false;
        return new File(new File(rootfsDirPath + GUEST_ZONEINFO_DIR), timeZoneId).isFile();
    }

    /**
     * Build the default proot guest command: {@code proot -r <rootfs> -0 -w /root
     * -b /dev -b <app-shm>:/dev/shm -b /dev/pts -b /proc -b /sys -b /system -b /apex -b /linkerconfig -b /data/app -b /sdcard:/root/sdcard
     * -b /storage:/root/storage /bin/bash --login [extraArgs...]}.
     *
     * <p>When the linkfix shim is installed, the guest program is wrapped as
     * {@code /usr/bin/env LD_PRELOAD=<shim> /bin/bash --login ...} so the
     * preload applies to guest (glibc) processes only. It must never be in
     * the host process environment: the host proot binary (Bionic) would fail
     * to start trying to preload a guest-absolute path.</p>
     *
     * <p>Storage binds mirror proot-distro's default mode but land under the guest
     * home ({@code /root/sdcard}, {@code /root/storage}) so the shared storage is
     * reachable from the file manager home without cluttering the rootfs top level.
     * A missing source is inert
     * (proot only warns) and the kernel still enforces the Android storage permission,
     * so no permission is bypassed.</p>
     *
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    public static String[] buildProotCommand(@Nullable String[] extraArgs) {
        return buildProotCommand(GUEST_HOME, extraArgs, isLinkfixInstalledOnHost());
    }

    /**
     * Build the proot guest command starting in a specific guest working directory.
     *
     * <p>Use instead of {@link #buildProotCommand(String[])} when a session must
     * open in a directory selected from the file manager. The guest path (see
     * {@link #hostPathToGuestPath(String)}) is passed via {@code -w} so the
     * selected directory actually becomes the session working directory.</p>
     *
     * @param guestWorkingDirectory The guest path to use as {@code -w}, e.g. {@code /root/sdcard}.
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    public static String[] buildProotCommand(@NonNull String guestWorkingDirectory, @Nullable String[] extraArgs) {
        return buildProotCommand(guestWorkingDirectory, extraArgs, isLinkfixInstalledOnHost());
    }

    /**
     * Build the proot guest command with explicit control over the linkfix
     * wrapper (the no-arg variant probes the on-device rootfs).
     *
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @param withLinkfix Whether to wrap the guest program with
     * {@code /usr/bin/env LD_PRELOAD=<shim>}.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    static String[] buildProotCommand(@Nullable String[] extraArgs, boolean withLinkfix) {
        return buildProotCommand(GUEST_HOME, extraArgs, withLinkfix);
    }

    /**
     * Build the proot guest command with explicit guest working directory and
     * linkfix control.
     *
     * @param guestWorkingDirectory The guest path to use as {@code -w}.
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @param withLinkfix Whether to wrap the guest program with
     * {@code /usr/bin/env LD_PRELOAD=<shim>}.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    static String[] buildProotCommand(@NonNull String guestWorkingDirectory, @Nullable String[] extraArgs, boolean withLinkfix) {
        return buildProotCommand(guestWorkingDirectory, extraArgs, withLinkfix, null);
    }

    /**
     * Build the proot guest command with explicit guest working directory,
     * linkfix control and procfs overlay binds.
     *
     * @param guestWorkingDirectory The guest path to use as {@code -w}.
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @param withLinkfix Whether to wrap the guest program with
     * {@code /usr/bin/env LD_PRELOAD=<shim>}.
     * @param overlayBinds Optional file binds for blocked procfs reads (spec
     * 010), as {@code {host, guest}} pairs appended right after {@code -b /proc}
     * so the specific file wins over the directory bind; {@code null} or empty
     * keeps the command identical to the non-overlay layout.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    public static String[] buildProotCommand(@Nullable String guestWorkingDirectory,
                                             @Nullable String[] extraArgs,
                                             boolean withLinkfix,
                                             @Nullable List<String[]> overlayBinds) {
        List<String> command = new ArrayList<>();
        command.add(TermuxConstants.PROOT_BIN_PATH);
        command.add("-r");
        command.add(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH);
        command.add("-0");
        command.add("-w");
        command.add(guestWorkingDirectory);
        command.add("-b");
        command.add("/dev");
        command.add("-b");
        command.add(TermuxConstants.DEBIAN_SHM_DIR_PATH + ":/dev/shm");
        command.add("-b");
        command.add("/dev/pts");
        command.add("-b");
        command.add("/proc");
        // Synthetic proc snapshots (spec 010): the more specific bind destination
        // /proc/<file> shadows the /proc directory bind for that file only.
        if (overlayBinds != null) {
            for (String[] bind : overlayBinds) {
                if (bind == null || bind.length < 2 || bind[0] == null || bind[1] == null)
                    continue;
                command.add("-b");
                command.add(bind[0] + ":" + bind[1]);
            }
        }
        command.add("-b");
        command.add("/sys");
        // Android /system read-only (spec 014, RF-8): lets the guest run the X11
        // companion paste command (/system/bin/pm, /system/bin/app_process are
        // hidden without it). Kernel permissions and SELinux are unchanged.
        command.add("-b");
        command.add("/system");
        // APEX runtime (Android 11+): app_process's ELF interpreter is
        // /apex/com.android.runtime/bin/linker64 and ART libs live under
        // /apex/com.android.art; proot translates unbound paths to the rootfs,
        // which has no /apex, so the companion would die with ENOENT at exec
        // ("required file not found"). Read-only for the same-uid guest.
        command.add("-b");
        command.add("/apex");
        // Persistent linker config generated at boot by the linkerconfig module:
        // without it the APEX linker runs with zero namespaces and cannot resolve
        // apex-hosted libs (libnativeloader.so, libart.so) — CANNOT LINK EXECUTABLE.
        command.add("-b");
        command.add("/linkerconfig");
        // APK install tree (spec 014, enmienda A): `pm path` output and the
        // TERMUX_X11_APK classpath must be resolvable inside the guest, otherwise
        // proot would translate /data/app/* into the rootfs and the companion
        // could not load its APK dex.
        command.add("-b");
        command.add("/data/app");
        command.add("-b");
        command.add("/sdcard:" + GUEST_HOME + "/sdcard");
        command.add("-b");
        command.add("/storage:" + GUEST_HOME + "/storage");
        if (withLinkfix) {
            command.add("/usr/bin/env");
            command.add(ENV_LD_PRELOAD + "=" + TermuxConstants.LINKFIX_GUEST_SO_PATH);
        }
        command.add(GUEST_SHELL);
        command.add("--login");
        if (extraArgs != null) Collections.addAll(command, extraArgs);
        return command.toArray(new String[0]);
    }

    /** Get guest environment for the Debian rootfs. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {
        // Guest environment builds upon the Android environment (TERM, ANDROID_*).
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        environment.put(ENV_HOME, GUEST_HOME);
        environment.put(ENV_USER, GUEST_USER);
        environment.put(ENV_LOGNAME, GUEST_USER);
        environment.put(ENV_SHELL, GUEST_SHELL);
        environment.put(ENV_PATH, GUEST_PATH);
        environment.put(ENV_TMPDIR, "/tmp");
        environment.put(ENV_PROOT_TMP_DIR, TermuxConstants.TERMUX_FILES_DIR_PATH);
        // Deterministic minimal-rootfs locale: C.UTF-8 always exists (see locale -a),
        // while the inherited en_US.UTF-8 triggers perl "Setting locale failed" warnings.
        environment.put(ENV_LANG, GUEST_LANG);
        environment.put(ENV_LC_ALL, GUEST_LANG);
        environment.put(ENV_LANGUAGE, "C");
        environment.put(ENV_DEBIAN_FRONTEND, GUEST_DEBIAN_FRONTEND);

        // Export the device timezone so glibc shows the local time: without TZ the
        // guest falls back to the rootfs /etc/localtime (UTC in a fresh rootfs).
        // Gated on the presence of the zonefile in the guest tzdata (see
        // isGuestTimeZoneAvailable); recomputed per command so runtime timezone
        // changes (device settings) are picked up on the next session.
        String guestTimeZoneId = TimeZone.getDefault().getID();
        if (isGuestTimeZoneAvailable(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH, guestTimeZoneId))
            environment.put(ENV_TZ, guestTimeZoneId);

        // Advertise kitty terminal branding so yazi and other KGP-aware TUIs select the
        // Kitty Graphics Protocol image adapter without requiring manual env setup.
        environment.put(ENV_KITTY_WINDOW_ID, "1");
        environment.put(ENV_TERM_PROGRAM, "kitty");

        // Spec 011 (RF-4): while gpu_solution=ADRENO_KGSL is persisted, the guest Mesa
        // loader is forced to the KGSL gallium driver (Adreno acceleration). The pref is
        // read here from the caller-supplied context (TermuxService passes itself to the
        // session), so every proot guest command picks it up and nothing else does.
        // Inactive → the map is identical to the pre-spec guest environment (NFR-3).
        boolean gpuActive = isGpuSolutionActive(currentPackageContext);
        applyGpuEnv(environment, gpuActive);

        // Spec 013 (RF-3): VirGL acceleration via the native vtest server. Injected
        // only when the pref is VIRGL AND the server socket actually exists at the
        // rootfs /tmp; a dead server degrades to the pre-spec environment with the
        // missing socket logged (RF-9), never silently guessing drivers.
        boolean virglActive = isVirglSolutionActive(currentPackageContext);
        if (virglActive) {
            boolean socketPresent = isVirglSocketPresent();
            if (!socketPresent)
                Logger.logWarn(LOG_TAG, "gpu_solution=VIRGL but the vtest socket is not present at " + TermuxConstants.DEBIAN_ROOTFS_TMP_DIR_PATH + " (guest env stays inert)");
            applyVirglSessionEnv(environment, virglActive, socketPresent, TermuxConstants.DEBIAN_ROOTFS_TMP_DIR_PATH);
        }

        // Spec 012 (RF-1, RF-2, RF-4): multithreaded llvmpipe rasterizer. Injected
        // into the guest environment when enabled and no GPU path (KGSL or VirGL)
        // is active, because the threads variables configure the CPU rasterizer.
        boolean llvmpipeThreadsEnabled = isLlvmpipeThreadsEnabled(currentPackageContext);
        if (shouldInjectLlvmpipeThreads(llvmpipeThreadsEnabled, gpuActive, virglActive)) {
            applyLlvmpipeEnv(environment,
                true,
                false,
                Runtime.getRuntime().availableProcessors(),
                getLlvmpipeThreadsCount(currentPackageContext));
        }

        // Spec 014 (enmienda A): host APK path for the guest termux-x11 companion
        // wrapper (its CLASSPATH). Computed per command so app updates are picked
        // up automatically; skipped when the path cannot be resolved (never
        // breaks existing sessions).
        try {
            TermuxX11GuestLoaderLibrary.applyApkPathEnv(environment,
                currentPackageContext.getApplicationInfo().sourceDir);
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG,
                "Failed to resolve application APK path for " + TermuxX11GuestLoaderLibrary.ENV_X11_APK + "\n" + e.toString());
        }

        // termux-exec conflicts with proot: never propagate these. LD_PRELOAD
        // must also stay out of the host proot process environment (the host
        // binary could not load a guest-absolute preload path); the linkfix
        // shim is injected into the guest argv via /usr/bin/env instead
        // (see buildProotCommand).
        environment.remove(ENV_LD_LIBRARY_PATH);
        environment.remove(ENV_LD_PRELOAD);

        return environment;
    }

    @NonNull
    @Override
    public HashMap<String, String> setupShellCommandEnvironment(@NonNull Context currentPackageContext,
                                                                @NonNull ExecutionCommand executionCommand) {
        HashMap<String, String> environment = getEnvironment(currentPackageContext, executionCommand.isFailsafe);

        String workingDirectory = executionCommand.workingDirectory;
        environment.put(ENV_PWD,
            workingDirectory != null && !workingDirectory.isEmpty() ? workingDirectory :
            getDefaultWorkingDirectoryPath());

        // NOTE: no createHomeDir() here: guest $HOME (/root) does not exist on
        // the host and must not be created there.
        if (executionCommand.setShellCommandShellEnvironment && shellCommandShellEnvironment != null)
            environment.putAll(shellCommandShellEnvironment.getEnvironment(currentPackageContext, executionCommand));

        return environment;
    }

    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.APP_BIN_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        // Passthrough: the executable is already fully resolved (proot binary).
        // Must not fall back to $PREFIX interpreters like TermuxShellUtils does.
        List<String> result = new ArrayList<>();
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

}
