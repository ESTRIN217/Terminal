package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.File;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

/**
 * App first-run setup (no Termux bootstrap in this fork).
 * <p/>
 * (1) Verify the app is running as the primary user with an accessible files directory.
 * <p/>
 * (2) Ensure base directories ({@code files/}, home) exist.
 * <p/>
 * The bundled proot binary ({@link #installProotIfNeeded(Context)}) and the Debian
 * rootfs (DebianInstaller) are installed separately after this step.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // Ensure app directories exist
        FileUtils.createDirectoryFile(TermuxConstants.TERMUX_FILES_DIR_PATH);
        FileUtils.createDirectoryFile(TermuxConstants.TERMUX_HOME_DIR_PATH);

        whenDone.run();
    }

    /**
     * Copy the bundled proot binary (Fase 2B asset) to {@link TermuxConstants#PROOT_BIN_PATH}
     * if necessary and make it executable.
     *
     * @param context The {@link Context} for operations.
     * @return Returns the {@link Error} if installation failed, otherwise {@code null}.
     */
    static Error installProotIfNeeded(final Context context) {
        return installEmbeddedBinaryIfNeeded(context, TermuxConstants.PROOT_BIN_PATH,
            TermuxConstants.PROOT_ASSET_PATH, "proot");
    }

    /**
     * Installs the bundled VirGL server binary on first use (spec 013, RF-1/RF-9).
     * Missing asset (old APK / ABI mismatch) yields an Error object — never a
     * thrown exception (RF-9).
     *
     * @param context The Android {@link Context} whose assets carry the binary.
     * @return Returns {@code null} on success (convention of the repo), or an {@link Error}.
     */
    static Error installVirglIfNeeded(final Context context) {
        return installEmbeddedBinaryIfNeeded(context, TermuxConstants.VIRGL_BIN_PATH,
            TermuxConstants.VIRGL_ASSET_PATH, "VirGL server");
    }

    /**
     * Install (or upgrade) an embedded asset binary, shared by
     * {@link #installProotIfNeeded} and {@link #installVirglIfNeeded}
     * (spec 018, Enmienda A, RF-05): copies from assets, chmod 0700, verifies
     * executability. A previously installed binary is kept only when its length
     * matches the current asset, so APK updates actually replace embedded
     * binaries instead of silently keeping stale copies forever.
     *
     * @param context The Android {@link Context} whose assets carry the binary.
     * @param binPath Absolute install destination path of the binary.
     * @param assetPath Assets-relative path of the bundled binary.
     * @param label Human-readable name for logs.
     * @return Returns {@code null} on success (convention of the repo), or an {@link Error}.
     */
    static Error installEmbeddedBinaryIfNeeded(final Context context, final String binPath,
                                               final String assetPath, final String label) {
        File bin = new File(binPath);
        if (bin.isFile() && bin.canExecute()) {
            long assetLength = getAssetLength(context, assetPath);
            if (assetLength < 0) {
                Error error = Errno.ERRNO_FAILED.getError(
                    "Failed to read the bundled \"" + label + "\" asset \"" + assetPath + "\"; keeping the installed binary");
                Logger.logErrorExtended(LOG_TAG, "Failed\n" + error);
                return null;
            }
            if (!binaryNeedsUpgrade(bin.length(), assetLength)) {
                Logger.logDebug(LOG_TAG, label + " already installed and current at \"" + binPath + "\".");
                return null;
            }
            Logger.logInfo(LOG_TAG, "Upgrading " + label + " from assets to \"" + binPath + "\".");
        } else {
            Logger.logInfo(LOG_TAG, "Installing " + label + " from assets to \"" + binPath + "\".");
        }

        Error error = TermuxFileUtils.isTermuxAppBinDirectoryAccessible(true, true);
        if (error != null) return error;

        try (java.io.InputStream in = context.getAssets().open(assetPath);
             java.io.OutputStream out = new java.io.FileOutputStream(bin)) {
            byte[] buffer = new byte[32768];
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1)
                out.write(buffer, 0, readBytes);
        } catch (Exception e) {
            return new Error("Failed to copy " + label + " binary to \"" + binPath + "\".", e);
        }

        try {
            android.system.Os.chmod(bin.getAbsolutePath(), 0700);
        } catch (Exception e) {
            return new Error("Failed to chmod 0700 " + label + " binary at \"" + binPath + "\".", e);
        }

        if (!bin.canExecute())
            return new Error(label + " binary at \"" + binPath + "\" is not executable after install.");

        Logger.logInfo(LOG_TAG, label + " installed successfully.");
        return null;
    }

    /**
     * Whether an installed embedded binary must be replaced by the bundled
     * asset (spec 018, Enmienda A, RF-05, pure).
     *
     * @param installedLength Length of the binary currently installed on disk.
     * @param assetLength     Length of the current asset (unmeasurable means upgrade).
     * @return {@code true} when the install copy must be (re)written.
     */
    static boolean binaryNeedsUpgrade(long installedLength, long assetLength) {
        if (assetLength <= 0)
            return true;
        return installedLength != assetLength;
    }

    /**
     * Total byte length of a bundled asset by streaming it (spec 018, Enmienda A).
     *
     * @param context   The Android {@link Context} whose assets carry the file.
     * @param assetPath Assets-relative path.
     * @return The length in bytes, or {@code -1} when the asset cannot be read.
     */
    private static long getAssetLength(final Context context, final String assetPath) {
        try (java.io.InputStream in = context.getAssets().open(assetPath)) {
            byte[] buffer = new byte[32768];
            long length = 0;
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1)
                length += readBytes;
            return length;
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to size asset \"" + assetPath + "\"\n" + e.getMessage());
            return -1;
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

}
