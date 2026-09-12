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

    /** Guest user and home (proot {@code -0} maps to root). */
    public static final String GUEST_USER = "root";
    public static final String GUEST_HOME = "/root";
    public static final String GUEST_SHELL = "/bin/bash";

    /** Guest {@code PATH} (pure Debian FHS, no host paths). */
    public static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

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
     * Build the default proot guest command: {@code proot -r <rootfs> -0 -w /root
     * -b /dev -b /proc -b /sys -b /sdcard -b /storage /bin/bash --login [extraArgs...]}.
     *
     * <p>Storage binds mirror proot-distro's default mode. A missing source is inert
     * (proot only warns) and the kernel still enforces the Android storage permission,
     * so no permission is bypassed.</p>
     *
     * @param extraArgs Optional extra args appended after {@code --login}, may be {@code null}.
     * @return Returns the full command array with the proot binary first.
     */
    @NonNull
    public static String[] buildProotCommand(@Nullable String[] extraArgs) {
        List<String> command = new ArrayList<>();
        command.add(TermuxConstants.PROOT_BIN_PATH);
        command.add("-r");
        command.add(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH);
        command.add("-0");
        command.add("-w");
        command.add(GUEST_HOME);
        command.add("-b");
        command.add("/dev");
        command.add("-b");
        command.add("/proc");
        command.add("-b");
        command.add("/sys");
        command.add("-b");
        command.add("/sdcard");
        command.add("-b");
        command.add("/storage");
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

        // termux-exec conflicts with proot: never propagate these.
        environment.remove(ENV_LD_LIBRARY_PATH);
        environment.remove("LD_PRELOAD");

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
