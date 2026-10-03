package com.termux.app;

import android.content.Context;
import android.system.Os;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.ProotShellEnvironment;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Download, verify and extract the Debian arm64 rootfs (Fase 3).
 *
 * <p>The rootfs tarball comes from {@code termux/proot-distro} releases and is
 * independent of the app package name, which is what makes this fork renamable.
 * Installation is atomic: everything goes to the staging directory first and is
 * renamed to the final location only after verification, extraction and post
 * configuration succeed.</p>
 */
final class DebianInstaller {

    private static final String LOG_TAG = "DebianInstaller";

    /** Installation phases reported to {@link Listener#onProgress(Phase, long, long)}. */
    enum Phase {
        /** Downloading the {@code .tar.gz} tarball. {@code total} is bytes, may be estimated. */
        DOWNLOADING,
        /** Verifying the SHA-256 of a previously downloaded tarball. */
        VERIFYING,
        /** Extracting entries. {@code done} is extracted files, {@code total} is {@code -1}. */
        EXTRACTING,
        /** Writing post-install configuration files. */
        CONFIGURING,
        /** Moving staging to the final location. */
        MOVING
    }

    /**
     * Listener for installation events. All callbacks are invoked on the installer
     * worker thread; implementations must marshal to the main thread if needed.
     */
    interface Listener {
        /**
         * Called periodically with installation progress.
         *
         * @param phase The current {@link Phase}.
         * @param done Units done (bytes or files depending on phase).
         * @param total Total units, or {@code -1} if unknown.
         */
        void onProgress(Phase phase, long done, long total);

        /** Called when installation finished successfully. */
        void onFinished();

        /**
         * Called when installation failed.
         *
         * @param error The {@link Error} describing the failure.
         */
        void onError(Error error);
    }

    private static volatile boolean sInstalling;
    private static volatile Listener sListener;

    /** Whether an installation is currently running. */
    static synchronized boolean isInstalling() {
        return sInstalling;
    }

    /**
     * Replace the listener of a running installation (e.g. after rotation).
     *
     * @param listener The new {@link Listener}, may be {@code null}.
     */
    static synchronized void setListener(Listener listener) {
        sListener = listener;
    }

    /**
     * Whether a usable Debian rootfs is installed.
     *
     * @return Returns {@code true} if {@code bin/bash} and {@code etc/debian_version}
     * exist under {@link TermuxConstants#DEBIAN_ROOTFS_DIR_PATH}.
     */
    public static boolean isInstalled() {
        File bash = new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/bin/bash");
        File version = new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/etc/debian_version");
        return bash.isFile() && version.isFile();
    }

    /**
     * Start installation on a worker thread. If already installed, the listener is
     * notified immediately. If an installation is already running, the listener is
     * attached to it.
     *
     * @param context The {@link Context} for operations (application context preferred).
     * @param listener The {@link Listener} for events.
     */
    static synchronized void install(Context context, Listener listener) {
        if (isInstalled()) {
            Logger.logInfo(LOG_TAG, "Debian rootfs already installed, repairing permissions.");
            // Best-effort repair for rootfs extracted before mode preservation
            // (0700 on tmp/var/lib/dpkg breaks dpkg even as fake root) and for
            // installing the link(2)-emulation shim (Fase 6).
            Context appContext = context.getApplicationContext();
            new Thread(() -> {
                Error repairError = repairInstalledRootfsPermissions(appContext);
                if (repairError != null)
                    Logger.logErrorExtended(LOG_TAG, "Debian permission repair failed:\n" + repairError);
                if (listener != null) listener.onFinished();
            }, "debian-permission-repair").start();
            return;
        }
        if (sInstalling) {
            Logger.logInfo(LOG_TAG, "Debian installation already running, attaching listener.");
            sListener = listener;
            return;
        }
        sInstalling = true;
        sListener = listener;
        Context appContext = context.getApplicationContext();
        new Thread(() -> runInstall(appContext), "debian-installer").start();
    }

    private static void reportProgress(Phase phase, long done, long total) {
        Listener listener = sListener;
        if (listener != null) {
            try {
                listener.onProgress(phase, done, total);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Debian installer listener onProgress failed: " + e.getMessage());
            }
        }
    }

    private static void reportFinished() {
        sInstalling = false;
        Listener listener = sListener;
        sListener = null;
        if (listener != null) {
            try {
                listener.onFinished();
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Debian installer listener onFinished failed: " + e.getMessage());
            }
        }
    }

    private static void reportError(Error error) {
        Logger.logErrorExtended(LOG_TAG, "Debian installation failed:\n" + error);
        sInstalling = false;
        Listener listener = sListener;
        sListener = null;
        if (listener != null) {
            try {
                listener.onError(error);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Debian installer listener onError failed: " + e.getMessage());
            }
        }
    }

    private static void runInstall(Context context) {
        try {
            Logger.logInfo(LOG_TAG, "Installing Debian rootfs from " + TermuxConstants.DEBIAN_ROOTFS_TARBALL_URL);

            File tarball = new File(TermuxConstants.DEBIAN_ROOTFS_TARBALL_FILE_PATH);
            File staging = new File(TermuxConstants.DEBIAN_STAGING_ROOTFS_DIR_PATH);

            Error error = FileUtils.deleteFile("debian staging directory", staging.getAbsolutePath(), true);
            if (error != null) {
                reportError(error);
                return;
            }

            if (!isTarballValid(tarball)) {
                error = downloadTarball(tarball);
                if (error != null) {
                    reportError(error);
                    return;
                }
            }

            reportProgress(Phase.EXTRACTING, 0, -1);
            error = extractTarball(tarball, staging);
            if (error != null) {
                reportError(error);
                return;
            }

            reportProgress(Phase.CONFIGURING, 0, -1);
            error = writePostInstallConfig(staging, context);
            if (error != null) {
                reportError(error);
                return;
            }

            reportProgress(Phase.MOVING, 0, -1);
            error = FileUtils.deleteFile("debian rootfs directory", TermuxConstants.DEBIAN_ROOTFS_DIR_PATH, true);
            if (error != null) {
                reportError(error);
                return;
            }
            if (!staging.renameTo(new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH))) {
                reportError(new Error("Failed to move debian staging directory to \"" + TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "\"."));
                return;
            }
            FileUtils.deleteFile("debian tarball", tarball.getAbsolutePath(), true);

            if (!isInstalled()) {
                reportError(new Error("Debian installation finished but \"" + TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/bin/bash\" is missing."));
                return;
            }

            // Snapshot of the guest environment for debugging (files/debian.env).
            ProotShellEnvironment.writeEnvironmentToFile(context);

            Logger.logInfo(LOG_TAG, "Debian rootfs installed successfully.");
            reportFinished();
        } catch (Exception e) {
            reportError(new Error("Debian installation failed with exception.", e));
        }
    }

    /**
     * Check if the tarball exists and matches the pinned SHA-256.
     *
     * @param tarball The tarball {@link File}.
     * @return Returns {@code true} if valid.
     */
    private static boolean isTarballValid(File tarball) {
        if (!tarball.isFile() || tarball.length() == 0) return false;
        try {
            reportProgress(Phase.VERIFYING, 0, tarball.length());
            String sha256 = sha256OfFile(tarball, null);
            return TermuxConstants.DEBIAN_ROOTFS_TARBALL_SHA256.equalsIgnoreCase(sha256);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to verify existing tarball, re-downloading: " + e.getMessage());
            return false;
        }
    }

    /**
     * Download the tarball with streaming SHA-256.
     *
     * @param tarball The destination {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error downloadTarball(File tarball) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(TermuxConstants.DEBIAN_ROOTFS_TARBALL_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                return new Error("Debian tarball download failed with HTTP " + responseCode + ".");

            long total = connection.getContentLengthLong();
            if (total <= 0) total = TermuxConstants.DEBIAN_ROOTFS_TARBALL_SIZE;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long downloaded = 0;
            byte[] buffer = new byte[32768];
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(tarball)) {
                int readBytes;
                while ((readBytes = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, readBytes);
                    out.write(buffer, 0, readBytes);
                    downloaded += readBytes;
                    reportProgress(Phase.DOWNLOADING, downloaded, total);
                }
            }

            String sha256 = toHexString(digest.digest());
            if (!TermuxConstants.DEBIAN_ROOTFS_TARBALL_SHA256.equalsIgnoreCase(sha256)) {
                FileUtils.deleteFile("debian tarball with bad checksum", tarball.getAbsolutePath(), true);
                return new Error("Debian tarball SHA-256 mismatch: expected "
                    + TermuxConstants.DEBIAN_ROOTFS_TARBALL_SHA256 + ", got " + sha256 + ".");
            }
            return null;
        } catch (Exception e) {
            return new Error("Debian tarball download failed.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Extract the {@code .tar.gz} OCI layer tarball into the staging directory.
     *
     * <p>Hardlinks are recreated with {@code link(2)} (copy fallback) and OCI
     * whiteouts are skipped, mirroring proot-distro hardening.</p>
     *
     * @param tarball The tarball {@link File}.
     * @param staging The staging directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error extractTarball(File tarball, File staging) {
        long extractedFiles = 0;
        try (InputStream fis = new FileInputStream(tarball);
             InputStream gzip = new GzipCompressorInputStream(new BufferedInputStream(fis));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = tar.getNextEntry()) != null) {
                String name = sanitizeEntryName(entry.getName());
                if (name == null) {
                    Logger.logError(LOG_TAG, "Skipping unsafe tar entry: \"" + entry.getName() + "\".");
                    continue;
                }
                if (isWhiteoutEntry(name)) {
                    Logger.logDebug(LOG_TAG, "Skipping OCI whiteout entry: \"" + name + "\".");
                    continue;
                }
                File out = new File(staging, name);
                if (entry.isDirectory()) {
                    Error error = FileUtils.createDirectoryFile(out.getAbsolutePath());
                    if (error != null) return error;
                    // Preserve tar dir mode (755, 1777 for /tmp, ...). mkdirs() alone
                    // inherits the app umask and produced 0700 dirs that break apt/dpkg.
                    applyMode(out, entry.getMode());
                } else if (entry.isSymbolicLink()) {
                    Error error = ensureParentExists(out);
                    if (error != null) return error;
                    FileUtils.deleteFile("debian symlink target", out.getAbsolutePath(), true);
                    try {
                        Os.symlink(entry.getLinkName(), out.getAbsolutePath());
                    } catch (Exception e) {
                        return new Error("Failed to create symlink \"" + out.getAbsolutePath() + "\" -> \"" + entry.getLinkName() + "\".", e);
                    }
                    extractedFiles++;
                } else if (entry.isLink()) {
                    Error error = applyHardlinkEntry(staging, out, entry.getLinkName(), buffer);
                    if (error != null) return error;
                    extractedFiles++;
                } else if (entry.isFile()) {
                    Error error = ensureParentExists(out);
                    if (error != null) return error;
                    try (OutputStream fileOut = new FileOutputStream(out)) {
                        int readBytes;
                        while ((readBytes = tar.read(buffer)) != -1)
                            fileOut.write(buffer, 0, readBytes);
                    } catch (Exception e) {
                        return new Error("Failed to extract file \"" + out.getAbsolutePath() + "\".", e);
                    }
                    applyFileMode(out, entry.getMode());
                    extractedFiles++;
                } else {
                    Logger.logDebug(LOG_TAG, "Skipping special tar entry: \"" + entry.getName() + "\".");
                }
                if (extractedFiles % 500 == 0)
                    reportProgress(Phase.EXTRACTING, extractedFiles, -1);
            }
            reportProgress(Phase.EXTRACTING, extractedFiles, -1);
            // Fail loudly here (not later at isInstalled) if the shell is missing,
            // so extraction problems get error locality.
            if (!new File(staging, "bin/bash").isFile())
                return new Error("Extraction finished (" + extractedFiles + " files) but \"bin/bash\" is missing in staging.");
            return null;
        } catch (Exception e) {
            return new Error("Failed to extract Debian tarball.", e);
        }
    }

    /**
     * Whether a sanitized entry name is an OCI whiteout marker.
     *
     * @param name The sanitized entry name.
     * @return Returns {@code true} for {@code .wh.*} files and {@code .wh..wh..opq} markers.
     */
    static boolean isWhiteoutEntry(String name) {
        if (name == null) return false;
        for (String segment : name.split("/")) {
            if (segment.startsWith(".wh.")) return true;
        }
        return false;
    }

    /**
     * Recreate a tar hardlink inside staging, falling back to a copy.
     *
     * @param staging The staging directory {@link File}.
     * @param out The destination {@link File}.
     * @param linkName The raw hardlink target path inside the archive.
     * @param buffer Scratch buffer for the copy fallback.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error applyHardlinkEntry(File staging, File out, String linkName, byte[] buffer) {
        Error error = ensureParentExists(out);
        if (error != null) return error;
        String targetName = sanitizeEntryName(linkName);
        if (targetName == null)
            return new Error("Unsafe hardlink target \"" + linkName + "\" for \"" + out.getAbsolutePath() + "\".");
        File target = new File(staging, targetName);
        FileUtils.deleteFile("debian hardlink target", out.getAbsolutePath(), true);
        try {
            Os.link(target.getAbsolutePath(), out.getAbsolutePath());
            return null;
        } catch (Exception linkException) {
            Logger.logDebug(LOG_TAG, "link(2) failed for \"" + out.getAbsolutePath() + "\", copying instead.");
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(target));
             OutputStream fileOut = new FileOutputStream(out)) {
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1)
                fileOut.write(buffer, 0, readBytes);
        } catch (Exception e) {
            return new Error("Failed to copy hardlink target \"" + target.getAbsolutePath() + "\".", e);
        }
        if (target.canExecute())
            out.setExecutable(true, true);
        return null;
    }

    /**
     * Sanitize a tar entry name: reject absolute paths and {@code ..} traversal.
     *
     * @param name The raw entry name.
     * @return Returns the sanitized relative path, or {@code null} if unsafe.
     */
    static String sanitizeEntryName(String name) {
        if (name == null || name.isEmpty()) return null;
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        if (name.isEmpty()) return null;
        String[] segments = name.split("/");
        StringBuilder safe = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                if (segment.equals("..")) return null;
                continue;
            }
            if (safe.length() > 0) safe.append('/');
            safe.append(segment);
        }
        if (safe.length() == 0) return null;
        return safe.toString();
    }

    private static Error ensureParentExists(File out) {
        File parent = out.getParentFile();
        if (parent == null) return new Error("No parent for \"" + out.getAbsolutePath() + "\".");
        return FileUtils.createDirectoryFile(parent.getAbsolutePath());
    }

    private static void applyFileMode(File out, int mode) {
        // Preserve full permission bits (incl. sticky 01000 for /tmp). Best effort:
        // charging obscure suid/sgid bits must never abort the install.
        if (!chmodUnchecked(out.getAbsolutePath(), normalizeMode(mode))) {
            if ((mode & 0100) != 0 && !out.setExecutable(true, true))
                Logger.logError(LOG_TAG, "Failed to set executable bit on \"" + out.getAbsolutePath() + "\".");
        }
    }

    private static void applyMode(File out, int mode) {
        chmodUnchecked(out.getAbsolutePath(), normalizeMode(mode));
    }

    /**
     * Mask a tar entry mode down to permission bits (sticky/suid/sgid kept).
     *
     * @param mode Raw tar entry mode.
     * @return Returns {@code mode & 07777}.
     */
    static int normalizeMode(int mode) {
        return mode & 07777;
    }

    private static boolean chmodUnchecked(String path, int mode) {
        try {
            Os.chmod(path, mode);
            return true;
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "chmod " + Integer.toOctalString(mode) + " failed on \"" + path + "\": " + e.getMessage());
            return false;
        }
    }

    /**
     * Write post-install configuration: DNS and APT sandbox workaround for proot.
     *
     * @param staging The staging directory {@link File}.
     * @param context The {@link Context} to read the bundled linkfix asset.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error writePostInstallConfig(File staging, Context context) {
        Error error = FileUtils.writeTextToFile("debian resolv.conf",
            new File(staging, "etc/resolv.conf").getAbsolutePath(),
            StandardCharsets.UTF_8, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n", false);
        if (error != null) return error;

        error = FileUtils.createDirectoryFile(new File(staging, "etc/apt/apt.conf.d").getAbsolutePath());
        if (error != null) return error;

        error = FileUtils.writeTextToFile("debian apt sandbox config",
            new File(staging, "etc/apt/apt.conf.d/01norestrict").getAbsolutePath(),
            StandardCharsets.UTF_8, "APT::Sandbox::User \"root\";\n", false);
        if (error != null) return error;

        error = writeWelcomeMessage(staging);
        if (error != null) return error;

        error = writePs1Config(staging);
        if (error != null) return error;

        error = installLinkfixToRootfs(context, staging);
        if (error != null) return error;

        return enforceCriticalPermissions(staging);
    }

    /**
     * Write the login welcome-message profile script into the rootfs.
     *
     * <p>Debian's {@code /etc/profile} sources every {@code *.sh} script in
     * {@code /etc/profile.d/}, so bash {@code --login} sessions echo
     * {@link TermuxConstants#DEBIAN_WELCOME_SHELL_SCRIPT} on each start.</p>
     *
     * @param root The staging or live rootfs directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error writeWelcomeMessage(File root) {
        Error error = FileUtils.createDirectoryFile(new File(root, "etc/profile.d").getAbsolutePath());
        if (error != null) return error;
        error = FileUtils.writeTextToFile("debian welcome message",
            new File(root, TermuxConstants.DEBIAN_WELCOME_PROFILE_RELATIVE_PATH).getAbsolutePath(),
            StandardCharsets.UTF_8, TermuxConstants.DEBIAN_WELCOME_SHELL_SCRIPT + "\n", false);
        if (error != null) return error;
        chmodUnchecked(new File(root, TermuxConstants.DEBIAN_WELCOME_PROFILE_RELATIVE_PATH).getAbsolutePath(), 0644);
        return null;
    }

    /**
     * Write the login PS1 profile script into the rootfs.
     *
     * <p>Debian's {@code /etc/profile} sources every {@code *.sh} script in
     * {@code /etc/profile.d/} after {@code /etc/bash.bashrc}, so bash
     * {@code --login} sessions pick up
     * {@link TermuxConstants#DEBIAN_PS1_SHELL_SCRIPT} on each start.</p>
     *
     * @param root The staging or live rootfs directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error writePs1Config(File root) {
        Error error = FileUtils.createDirectoryFile(new File(root, "etc/profile.d").getAbsolutePath());
        if (error != null) return error;
        error = FileUtils.writeTextToFile("debian ps1 config",
            new File(root, TermuxConstants.DEBIAN_PS1_PROFILE_RELATIVE_PATH).getAbsolutePath(),
            StandardCharsets.UTF_8, TermuxConstants.DEBIAN_PS1_SHELL_SCRIPT + "\n", false);
        if (error != null) return error;
        chmodUnchecked(new File(root, TermuxConstants.DEBIAN_PS1_PROFILE_RELATIVE_PATH).getAbsolutePath(), 0644);
        return null;
    }

    /**
     * Enforce Debian-expected modes for paths dpkg/apt need.
     *
     * <p>Extraction alone cannot be trusted: {@code mkdirs()}/{@code FileOutputStream}
     * apply the app umask, which previously left {@code /tmp} and
     * {@code /var/lib/dpkg} at {@code 0700} and broke
     * {@code apt upgrade} (dpkg backup links, {@code status-old}).</p>
     *
     * @param root The staging or live rootfs directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error enforceCriticalPermissions(File root) {
        Error error = FileUtils.createDirectoryFile(new File(root, "var/lib/dpkg/updates").getAbsolutePath());
        if (error != null) return error;
        error = FileUtils.createDirectoryFile(new File(root, "run/shm").getAbsolutePath());
        if (error != null) return error;

        chmodUnchecked(new File(root, "tmp").getAbsolutePath(), 01777);
        chmodUnchecked(new File(root, "var/tmp").getAbsolutePath(), 01777);
        chmodUnchecked(new File(root, "var/lib/dpkg").getAbsolutePath(), 0755);
        chmodUnchecked(new File(root, "var/lib/dpkg/updates").getAbsolutePath(), 0755);
        chmodUnchecked(new File(root, "etc/apt").getAbsolutePath(), 0755);
        chmodUnchecked(new File(root, "etc/apt/apt.conf.d").getAbsolutePath(), 0755);
        chmodUnchecked(new File(root, "var/cache/apt/archives").getAbsolutePath(), 0755);
        chmodUnchecked(new File(root, "etc/resolv.conf").getAbsolutePath(), 0644);
        chmodUnchecked(new File(root, "etc/apt/apt.conf.d/01norestrict").getAbsolutePath(), 0644);
        chmodUnchecked(new File(root, TermuxConstants.LINKFIX_GUEST_SO_PATH.substring(1)).getAbsolutePath(), 0755);
        return null;
    }

    /**
     * Whether the link(2)-emulation shim is present in the live rootfs.
     *
     * @return Returns {@code true} if the shim exists in the installed rootfs.
     */
    static boolean isLinkfixInstalled() {
        return new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH
            + TermuxConstants.LINKFIX_GUEST_SO_PATH).isFile();
    }

    /**
     * Copy the bundled link(2)-emulation shim into a rootfs (Fase 6).
     *
     * <p>Some devices deny link(2)/linkat(2) inside app data entirely (Android 15
     * SELinux/filesystem policy: touch works, link fails with EACCES even as
     * fake root). dpkg requires hardlinks for every status update and file
     * backup, so without this shim dpkg is unusable there. The shim is
     * preloaded via {@code LD_PRELOAD} (see {@link ProotShellEnvironment})
     * and emulates links of regular files with copies.</p>
     *
     * @param context The {@link Context} to read the bundled asset.
     * @param root The staging or live rootfs directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error installLinkfixToRootfs(Context context, File root) {
        File dest = new File(root, TermuxConstants.LINKFIX_GUEST_SO_PATH.substring(1));
        Error error = FileUtils.createDirectoryFile(dest.getParent());
        if (error != null) return error;

        try (InputStream in = new BufferedInputStream(
                context.getAssets().open(TermuxConstants.LINKFIX_SO_ASSET_PATH));
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[32768];
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1)
                out.write(buffer, 0, readBytes);
        } catch (Exception e) {
            return new Error("Failed to install linkfix shim to \"" + dest.getAbsolutePath() + "\".", e);
        }

        if (!chmodUnchecked(dest.getAbsolutePath(), 0755))
            return new Error("Failed to chmod 0755 linkfix shim at \"" + dest.getAbsolutePath() + "\".");
        return null;
    }

    /**
     * Repair permissions of an already-installed rootfs in place.
     *
     * <p>Installs extracted before mode preservation shipped with
     * {@code 0700} on {@code tmp}, {@code var/tmp} and {@code var/lib/dpkg},
     * which makes {@code dpkg} fail with {@code Permission denied} on
     * {@code status-old} and backup links even as (fake) root. Also refreshes
     * the link(2)-emulation shim install if it differs from the bundled asset
     * (older shims predate nlink emulation for shadow-utils locking).</p>
     *
     * @param context The {@link Context} to read the bundled linkfix asset.
     * @return Returns the {@link Error} if no usable rootfs is installed,
     * otherwise {@code null} (chmods are best effort).
     */
    static Error repairInstalledRootfsPermissions(Context context) {
        if (!isInstalled())
            return new Error("No usable Debian rootfs installed at \"" + TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "\".");
        File root = new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH);
        if (linkfixNeedsRefresh(context,
            new File(root, TermuxConstants.LINKFIX_GUEST_SO_PATH.substring(1)))) {
            Error error = installLinkfixToRootfs(context, root);
            if (error != null) return error;
        }
        if (!isWelcomeMessageInstalled()) {
            Error error = writeWelcomeMessage(root);
            if (error != null) return error;
        }
        if (!isPs1ConfigInstalled()) {
            Error error = writePs1Config(root);
            if (error != null) return error;
        }
        return enforceCriticalPermissions(root);
    }

    /**
     * Whether the on-device linkfix shim differs from the one bundled in the APK.
     *
     * <p>The shim is only regenerated on install, so devices that kept an older
     * build (e.g. one predating nlink emulation for shadow-utils locking) would
     * never receive fixes. Byte comparison makes the idempotent repair refresh
     * a stale shim exactly once per updated APK.</p>
     *
     * @param context The {@link Context} to read the bundled linkfix asset.
     * @param installed The installed shim {@link File} inside the rootfs.
     * @return Returns {@code true} when missing or different from the bundled asset.
     */
    private static boolean linkfixNeedsRefresh(Context context, File installed) {
        if (!installed.isFile()) return true;
        try (InputStream asset = context.getAssets().open(TermuxConstants.LINKFIX_SO_ASSET_PATH);
             InputStream disk = new BufferedInputStream(new FileInputStream(installed))) {
            byte[] a = new byte[8192];
            byte[] b = new byte[8192];
            while (true) {
                int na = readFully(asset, a);
                int nb = readFully(disk, b);
                if (na != nb) return true;
                if (na == 0) return false;
                for (int i = 0; i < na; i++) {
                    if (a[i] != b[i]) return true;
                }
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to compare linkfix asset with installed shim: " + e.getMessage());
            return true;
        }
    }

    /**
     * Read until the buffer is full or end-of-stream.
     *
     * @param in The {@link InputStream} to read from.
     * @param buf The destination buffer.
     * @return Returns the number of bytes read, or {@code 0} at end-of-stream.
     */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int read = in.read(buf, total, buf.length - total);
            if (read == -1) break;
            total += read;
        }
        return total;
    }

    /**
     * Whether the login welcome-message profile script is present in the live rootfs.
     *
     * @return Returns {@code true} if the script exists in the installed rootfs.
     */
    static boolean isWelcomeMessageInstalled() {
        return new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH
            + "/" + TermuxConstants.DEBIAN_WELCOME_PROFILE_RELATIVE_PATH).isFile();
    }

    /**
     * Whether the login PS1 profile script is present in the live rootfs.
     *
     * @return Returns {@code true} if the script exists in the installed rootfs.
     */
    static boolean isPs1ConfigInstalled() {
        return new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH
            + "/" + TermuxConstants.DEBIAN_PS1_PROFILE_RELATIVE_PATH).isFile();
    }

    private static String sha256OfFile(File file, long[] progressOut) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1)
                digest.update(buffer, 0, readBytes);
        }
        return toHexString(digest.digest());
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return hex.toString();
    }

}
