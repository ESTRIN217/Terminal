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

import java.nio.charset.Charset;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

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
     * Build the default proot guest command: {@code proot -r <rootfs> -0 -w /root
     * -b /dev -b <app-shm>:/dev/shm -b /dev/pts -b /proc -b /sys -b /sdcard:/root/sdcard
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
        command.add("-b");
        command.add("/sys");
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
