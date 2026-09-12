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
    static boolean isInstalled() {
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
            Logger.logInfo(LOG_TAG, "Debian rootfs already installed.");
            if (listener != null) listener.onFinished();
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
            error = writePostInstallConfig(staging);
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
                    applyExecutableBit(out, entry.getMode());
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

    private static void applyExecutableBit(File out, int mode) {
        // Preserve owner-executable bit from the tar entry (binaries, scripts).
        if ((mode & 0100) != 0 && !out.setExecutable(true, true))
            Logger.logError(LOG_TAG, "Failed to set executable bit on \"" + out.getAbsolutePath() + "\".");
    }

    /**
     * Write post-install configuration: DNS and APT sandbox workaround for proot.
     *
     * @param staging The staging directory {@link File}.
     * @return Returns the {@link Error} on failure, otherwise {@code null}.
     */
    private static Error writePostInstallConfig(File staging) {
        Error error = FileUtils.writeTextToFile("debian resolv.conf",
            new File(staging, "etc/resolv.conf").getAbsolutePath(),
            StandardCharsets.UTF_8, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n", false);
        if (error != null) return error;

        error = FileUtils.createDirectoryFile(new File(staging, "etc/apt/apt.conf.d").getAbsolutePath());
        if (error != null) return error;

        return FileUtils.writeTextToFile("debian apt sandbox config",
            new File(staging, "etc/apt/apt.conf.d/01norestrict").getAbsolutePath(),
            StandardCharsets.UTF_8, "APT::Sandbox::User \"root\";\n", false);
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
