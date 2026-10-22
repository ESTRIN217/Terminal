package com.termux.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.proot.GpuDebCatalog;
import com.termux.shared.termux.proot.GpuDetectionLibrary;
import com.termux.shared.termux.proot.GpuGuestCommands;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.ProotShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Download the six pinned Mesa {@code .deb} files (spec 011, RF-3, RF-7) and execute the
 * apt install/uninstall commands inside the Debian guest, following the
 * {@link DebianInstaller} streaming pattern ({@code HttpURLConnection} + in-flight
 * {@code MessageDigest} SHA-256).
 *
 * <p><b>Guest execution (T6):</b> after a verified {@link #downloadDebs}, the apt command
 * built by {@link GpuGuestCommands} runs as a ONE-SHOT background {@link TermuxSession}
 * inside the proot guest (same proven session path as {@code TermuxService
 * .createTermuxSession()}, plan «Interfaz del runner de comandos en el invitado»; the
 * {@code ProcessBuilder} alternative was discarded by the plan). The session transcript
 * is captured and ends with an {@code echo GPU_APT_EXIT:$?} marker whose pure-parse
 * resolution decides the {@code gpu_solution} preference transition: exit 0 promotes the
 * target solution (RF-3/RF-5), any other exit keeps the preference untouched and reports
 * {@link Error} + {@link Logger} + toast — never silence.</p>
 *
 * <p><b>Error/toast contract:</b> every failure is composed as an {@link Error} object,
 * logged via {@link Logger} and shown to the user via toast from this glue (same pattern
 * as {@code TermuxService}'s own failure toasts); the raw session transcript is also
 * written to {@link TermuxConstants#DEBIAN_GPU_LOG_FILE_PATH} for debugging.</p>
 *
 * <p><b>Threading:</b> {@link #installAcceleration} / {@link #uninstallAcceleration} are
 * asynchronous (apt takes minutes): they compose the guest session on a dedicated
 * background thread and deliver callback results on the main thread. An
 * {@link java.util.concurrent.atomic.AtomicBoolean} guard ({@link #isOperationInProgress})
 * prevents two concurrent GPU operations from ever running simultaneously.</p>
 *
 * <p><b>Action gate responsibility:</b> the caller (settings UI, T8) must only invoke
 * these methods once {@link GpuDetectionLibrary#evaluateAction} has offered the action;
 * this glue executes the pipeline after the gate allows it.</p>
 */
public final class GpuAccelerationInstaller {

    private static final String LOG_TAG = "GpuAccelerationInstaller";

    /** Suffix of the in-flight download file: it becomes the final {@code .deb} only after verification. */
    static final String PARTIAL_SUFFIX = ".partial";

    /** Connect/read timeouts, copied from {@link DebianInstaller#downloadTarball}. */
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** Streaming buffer size, copied from {@code DebianInstaller}. */
    private static final int DOWNLOAD_BUFFER_SIZE = 32768;

    /**
     * Cap for the one-shot apt session: {@code apt-get update} + reinstalling the six
     * {@code .deb} packages must finish in tens of seconds on a healthy connection;
     * 20 minutes is a generous ceiling. On timeout the session is SIGKILLed and the
     * operation fails (the preference never changes on an unverifiable result).
     */
    private static final long GUEST_SESSION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(20);

    /** Single-operation guard: only one GPU apt run may be in flight at a time. */
    private static final AtomicBoolean sOperationInProgress = new AtomicBoolean(false);

    /**
     * Callback of the asynchronous GPU acceleration operations; every callback is
     * delivered on the main thread.
     */
    public interface GpuAccelerationCallback {

        /**
         * Coarse progress of the pipeline (download phase, guest apt phase).
         *
         * @param message English phase text; the caller decides what to display.
         */
        void onProgress(String message);

        /**
         * The operation finished successfully and the preference was persisted.
         *
         * @param newSolution The solution just written to the {@code gpu_solution} pref.
         */
        void onSuccess(GpuDetectionLibrary.Solution newSolution);

        /**
         * The operation failed; the {@code gpu_solution} pref was NOT changed
         * (RF-3/RF-5). The error was already logged; a toast was also shown when the
         * failure happened in a user-visible stage.
         *
         * @param error The composed {@link Error}.
         */
        void onError(Error error);
    }

    /** No instances: this is a static utility class. */
    private GpuAccelerationInstaller() {
    }

    /**
     * Download and verify the six Mesa {@code .deb} files into
     * {@link TermuxConstants#DEBIAN_GPU_DEBS_DIR_PATH}, in catalog order.
     *
     * <p>Each download goes to a {@code <name>.partial} staging file with an in-flight
     * SHA-256. Only after {@link GpuDebCatalog#verifyHex(String, String)} matches the pinned
     * digest is the {@code .partial} renamed over the final {@code .deb}. The FIRST failure
     * aborts the whole batch (plan decision 4): nothing else is downloaded and nothing runs
     * in the guest.</p>
     *
     * @param context The {@link Context} for operations (application context preferred).
     *                Paths come from {@link TermuxConstants}; the context is kept in the
     *                signature for parity with {@link DebianInstaller} and future wiring.
     * @return {@code null} if all six files are downloaded and verified, otherwise the
     *         {@link Error} of the first failed download (package and URL included).
     */
    static Error downloadDebs(Context context) {
        try {
            Error error = FileUtils.createDirectoryFile(TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH);
            if (error != null)
                return error;

            for (int i = 0; i < GpuDebCatalog.DEB_URLS.length; i++) {
                String url = GpuDebCatalog.DEB_URLS[i];
                String packageName = GpuDebCatalog.PACKAGE_NAMES[i];
                String expectedSha256 = GpuDebCatalog.DEB_SHA256S[i];
                File destination = new File(TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH,
                    GpuDebCatalog.DEB_FILE_NAMES[i]);
                File partial = new File(TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH,
                    GpuDebCatalog.DEB_FILE_NAMES[i] + PARTIAL_SUFFIX);

                HttpURLConnection connection = openConnection(url);
                try {
                    Logger.logInfo(LOG_TAG, "Downloading \"" + packageName
                        + "\" from \"" + url + "\".");
                    error = downloadDeb(connection, url, packageName, expectedSha256,
                        destination, partial);
                } finally {
                    connection.disconnect();
                }
                if (error != null) {
                    // RF-7/plan [E3][E4]: abort early — never continue downloading, never apt.
                    Logger.logErrorExtended(LOG_TAG, "GPU .deb download aborted:\n" + error);
                    return error;
                }
            }
            Logger.logInfo(LOG_TAG, "All six Mesa .deb files downloaded and verified in \""
                + TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH + "\".");
            return null;
        } catch (Exception e) {
            Error error = new Error("GPU .deb download failed with exception.", e);
            Logger.logErrorExtended(LOG_TAG, "GPU .deb download failed:\n" + error);
            return error;
        }
    }

    /**
     * Download one {@code .deb} over an already-open connection.
     *
     * <p>An HTTP error status or a network exception is handled exactly like a SHA-256
     * mismatch: the {@code .partial} and any previous file are deleted and an
     * {@link Error} is returned (RF-7).</p>
     *
     * @param connection     The open {@link HttpURLConnection} for {@code url}.
     * @param url            The download URL (for error reporting).
     * @param packageName    The Debian package the {@code .deb} provides (for error reporting).
     * @param expectedSha256 The pinned SHA-256 of the file.
     * @param destination    The final {@code .deb} file path.
     * @param partial        The {@code .partial} staging file path.
     * @return {@code null} on verified success, otherwise the {@link Error}; never throws.
     */
    static Error downloadDeb(HttpURLConnection connection, String url, String packageName,
                             String expectedSha256, File destination, File partial) {
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Error error = new Error("Download of \"" + packageName + "\" from \"" + url
                    + "\" failed with HTTP " + responseCode + ".");
                deletePartialAndPrevious(partial, destination);
                return error;
            }
            try (InputStream in = connection.getInputStream()) {
                return downloadFromStream(url, packageName, expectedSha256, destination,
                    partial, in);
            }
        } catch (Exception e) {
            Error error = new Error("Download of \"" + packageName + "\" from \"" + url
                + "\" failed.", e);
            deletePartialAndPrevious(partial, destination);
            return error;
        }
    }

    /**
     * Stream a download source into the {@code .partial} file while digesting it, then
     * publish it to {@code destination} only if the digest matches
     * {@code expectedSha256}.
     *
     * <p>This is the testable extract of the pipeline: {@code source} is injected, so the
     * success shape, a mid-stream failure (plan [E3]) and a SHA-256 mismatch (plan [E4],
     * RF-7) are all unit-testable without network.</p>
     *
     * @param url            The download URL (for error reporting).
     * @param packageName    The Debian package the {@code .deb} provides (for error reporting).
     * @param expectedSha256 The pinned SHA-256 of the file.
     * @param destination    The final {@code .deb} file path.
     * @param partial        The {@code .partial} staging file path.
     * @param source         The raw download stream (already processed for HTTP status).
     * @return {@code null} on verified success, otherwise the {@link Error}; never throws.
     */
    static Error downloadFromStream(String url, String packageName, String expectedSha256,
                                    File destination, File partial, InputStream source) {
        try {
            String computedHex;
            try (InputStream in = new BufferedInputStream(source);
                 OutputStream out = new FileOutputStream(partial)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int readBytes;
                while ((readBytes = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, readBytes);
                    out.write(buffer, 0, readBytes);
                }
                out.flush();
                computedHex = toHexString(digest.digest());
            }

            if (!GpuDebCatalog.verifyHex(computedHex, expectedSha256)) {
                // [E4] constitución #5: mismatch → no useful artifact and apt never runs.
                Error error = new Error("SHA-256 mismatch for \"" + packageName
                    + "\" downloaded from \"" + url + "\": expected \"" + expectedSha256
                    + "\", got \"" + computedHex + "\".");
                deletePartialAndPrevious(partial, destination);
                return error;
            }

            // Verified: replace the previous file (if any) with the staged .partial.
            if (destination.exists() && !destination.delete()) {
                Error error = new Error("Failed to replace the previous file \"" + destination
                    .getAbsolutePath() + "\" of \"" + packageName + "\" downloaded from \""
                    + url + "\".");
                deletePartialAndPrevious(partial, destination);
                return error;
            }
            if (!partial.renameTo(destination)) {
                Error error = new Error("Failed to move the downloaded file \""
                    + partial.getAbsolutePath() + "\" to \"" + destination.getAbsolutePath()
                    + "\" for \"" + packageName + "\" downloaded from \"" + url + "\".");
                deletePartialAndPrevious(partial, destination);
                return error;
            }
            return null;
        } catch (Exception e) {
            // [E3] interrupted connection (or IO failure while publishing).
            Error error = new Error("Download of \"" + packageName + "\" from \"" + url
                + "\" failed.", e);
            deletePartialAndPrevious(partial, destination);
            return error;
        }
    }

    /**
     * Open a connection with the same timeouts and redirect handling as
     * {@link DebianInstaller#downloadTarball} (GitHub release assets redirect to S3).
     *
     * @param url The file URL.
     * @return The configured, not-yet-connected {@link HttpURLConnection}.
     * @throws IOException If the connection cannot be opened.
     */
    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    /**
     * Delete the {@code .partial} file and the previously published {@code .deb} of a failed
     * download (RF-7: no usable artifact may remain for a later apt run). Best effort:
     * deletion failures are logged, never thrown.
     *
     * @param partial     The {@code .partial} staging file, may not exist.
     * @param destination The previously published {@code .deb}, may not exist.
     */
    static void deletePartialAndPrevious(File partial, File destination) {
        try {
            if (partial.exists() && !partial.delete())
                Logger.logError(LOG_TAG, "Failed to delete the .partial file \""
                    + partial.getAbsolutePath() + "\".");
            if (destination.exists() && !destination.delete())
                Logger.logError(LOG_TAG, "Failed to delete the previous file \""
                    + destination.getAbsolutePath() + "\".");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to clean up artifacts of a failed download: "
                + e.getMessage());
        }
    }

    /**
     * Hex-encode a digest, copied from {@link DebianInstaller#toHexString}.
     *
     * @param bytes The digest bytes to encode.
     * @return The lowercase hex representation ({@code 2 * bytes.length} characters).
     */
    static String toHexString(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        return hex.toString();
    }

    /**
     * Whether the Debian rootfs is installed, delegated from the package-private
     * {@link DebianInstaller#isInstalled} so the Compose settings layer can gate
     * the GPU(action without importing the installer internals (T8, RF-3).
     *
     * @return {@code true} when a usable Debian rootfs is installed.
     */
    public static boolean isRootfsInstalled() {
        return DebianInstaller.isInstalled();
    }

    /**
     * Whether a GPU acceleration operation (install or uninstall) is currently in
     * flight (the single-operation guard). The settings screen (T8) can use this to
     * disable its actions while one runs.
     *
     * @return {@code true} while an operation is running.
     */
    public static boolean isOperationInProgress() {
        return sOperationInProgress.get();
    }

    /**
     * Asynchronously install the KGSL acceleration in the Debian guest (RF-3):
     * download + verify the six {@code .deb} packages (T5), then run
     * {@link GpuGuestCommands#buildInstallCommand} as a one-shot background proot
     * session and persist {@code gpu_solution=ADRENO_KGSL} on exit 0 only.
     *
     * <p>Failures (download, guest session, non-zero apt exit) log via
     * {@link Logger}, show a toast, write the session transcript to
     * {@link TermuxConstants#DEBIAN_GPU_LOG_FILE_PATH} and leave the preference
     * untouched.</p>
     *
     * @param context The {@link Context} for operations (application context preferred).
     * @param callback Receives the progress, the success solution or the composed
     *                 {@link Error}; delivered on the main thread.
     */
    public static void installAcceleration(@NonNull Context context,
                                           @Nullable GpuAccelerationCallback callback) {
        runOperation(context.getApplicationContext(), false, callback);
    }

    /**
     * Asynchronously uninstall the KGSL acceleration (RF-5): no downloads — run
     * {@link GpuGuestCommands#buildUninstallCommand} inside the guest (reinstall
     * from the Debian mirror, plan decision 1) and persist
     * {@code gpu_solution=NONE} on exit 0 only.
     *
     * @param context The {@link Context} for operations (application context preferred).
     * @param callback Same contract as in {@link #installAcceleration(Context, GpuAccelerationCallback)}.
     */
    public static void uninstallAcceleration(@NonNull Context context,
                                             @Nullable GpuAccelerationCallback callback) {
        runOperation(context.getApplicationContext(), true, callback);
    }

    /**
     * Callback of {@link #queryGuestMesaVersion(Context, GpuVersionCallback)}: the
     * guest mesa version read through {@code dpkg-query}, or {@code null} when the
     * rootfs is not installed, the runner failed or no queried package reported a
     * version (null counts as diverging for the action gate, RF-6). Delivered on
     * the main thread when a callback is present.
     */
    public interface GpuVersionCallback {

        /**
         * The resolved guest mesa version.
         *
         * @param version The first present version among the queried packages,
         *                or {@code null} when unknown.
         */
        void onVersion(@Nullable String version);
    }

    /**
     * Asynchronously read the guest mesa version through one-shot
     * {@code dpkg-query} inside the Debian guest (T8, RF-6): same session path
     * as the apt operations. Runs on a dedicated background thread (the guest
     * session blocks) and delivers {@link GpuVersionCallback#onVersion} on the
     * main thread; {@code null} means «unknown → diverges» for the gate.
     *
     * @param context The {@link Context} for operations (application context preferred).
     * @param callback Receives the version or {@code null}; delivered on the main thread.
     */
    public static void queryGuestMesaVersion(@NonNull Context context,
                                             @Nullable final GpuVersionCallback callback) {
        if (callback == null) {
            queryGuestMesaVersionBlocking(context);
            return;
        }
        Thread thread = new Thread(() ->
            deliverVersion(callback, queryGuestMesaVersionBlocking(context)), "gpu-mesa-version-query");
        thread.start();
    }

    /**
     * Blocking variant of {@link #queryGuestMesaVersion(Context, GpuVersionCallback)}
     * for background callers (the settings ViewModel runs it on its IO
     * dispatcher): it does NOT touch the operation guard (a read-only query
     * shares no state with an apt run) and never throws.
     *
     * <p>The answer is the common version across ALL six catalog packages
     * ({@link GpuGuestCommands#findCommonMesaVersion}): a partially diverging
     * guest counts as diverging for the pure gate (RF-3/RF-6), never as its
     * first present version.</p>
     *
     * @param context The application context.
     * @return The guest mesa version, or {@code null} when unknown; never throws.
     */
    @Nullable
    public static String queryGuestMesaVersionBlocking(@Nullable Context context) {
        if (context == null) {
            Logger.logErrorExtended(LOG_TAG, "Guest mesa version query rejected: context is null.");
            return null;
        }
        try {
            if (!DebianInstaller.isInstalled()) {
                // RF-6: without a rootfs there is nothing to query — the gate
                // blocks on the rootfs motive before the version matters.
                Logger.logInfo(LOG_TAG, "Debian rootfs not installed; guest mesa version unknown.");
                return null;
            }

            GuestSessionResult result = runGuestCommand(context.getApplicationContext(),
                GpuGuestCommands.assembleOneShotGuestCommand(
                    GpuGuestCommands.buildDpkgQueryCommand(GpuDebCatalog.PACKAGE_NAMES)),
                ProotShellEnvironment.GUEST_HOME,
                "GPU mesa version query (dpkg-query)");
            if (result.error != null) {
                Logger.logErrorExtended(LOG_TAG, "Guest mesa version query failed:\n" + result.error);
                return null;
            }

            Map<String, String> versions =
                GpuGuestCommands.parseDpkgVersions(result.transcript);
            String common = GpuGuestCommands.findCommonMesaVersion(versions);
            if (common != null) {
                Logger.logInfo(LOG_TAG, "Guest mesa version (all six packages): " + common + ".");
                return common;
            }
            // RF-3/RF-6: any absent or diverging package denies the gate with a
            // concrete motive; the never "first present wins" shortcut.
            Logger.logWarn(LOG_TAG, "Guest Mesa versions are absent or diverging (" + versions
                + "); the action gate reads this as a diverging guest (RF-6).");
            return null;
        } catch (Throwable throwable) {
            Logger.logErrorExtended(LOG_TAG, "Guest mesa version query failed with an exception:\n"
                + new Error("The guest mesa version query failed with an exception.", throwable));
            return null;
        }
    }

    /** Main-thread delivery of a guest mesa version. */
    private static void deliverVersion(@Nullable final GpuVersionCallback callback,
                                       final String version) {
        if (callback == null) return;
        postOnMainThread(() -> callback.onVersion(version));
    }

    /**
     * Guarded entry of the asynchronous operations: starts the dedicated background
     * thread (apt takes minutes) and releases the single-operation guard when the
     * pipeline ends for any reason. Concurrent second calls are rejected with an
     * {@link Error} — never queued, so the caller (T8) can just re-press the button.
     *
     * @param context   The application context.
     * @param uninstall {@code true} for the uninstall operation.
     * @param callback  The callback; may be {@code null} (operations log but then
     *                  have no main-thread reporting).
     */
    private static void runOperation(@Nullable Context context, boolean uninstall,
                                     @Nullable GpuAccelerationCallback callback) {
        if (context == null || callback == null) {
            Logger.logErrorExtended(LOG_TAG, "GPU acceleration operation rejected: "
                + (context == null ? "context" : "callback") + " is null.");
            return;
        }
        if (!sOperationInProgress.compareAndSet(false, true)) {
            Error error = new Error("A GPU acceleration operation is already in progress; "
                + "wait for it to finish before starting another one.");
            Logger.logErrorExtended(LOG_TAG, "Concurrent GPU acceleration operation rejected.\n" + error);
            deliverError(callback, error);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                executeOperation(context, uninstall, callback);
            } finally {
                sOperationInProgress.set(false);
            }
        }, uninstall ? "gpu-acceleration-uninstall" : "gpu-acceleration-install");
        thread.start();
    }

    /**
     * The full install/uninstall pipeline, running on the operation's background
     * thread: preference access, rootfs check, downloads (install only), one-shot
     * guest apt run, transcript log, exit resolution and preference transition
     * (RF-3, RF-5). Never throws: unexpected exceptions land in
     * {@link GpuAccelerationCallback#onError}.
     *
     * @param context   The application context.
     * @param uninstall {@code true} for the uninstall operation.
     * @param callback  The non-null callback.
     */
    private static void executeOperation(@NonNull Context context, boolean uninstall,
                                         @NonNull GpuAccelerationCallback callback) {
        final String verb = uninstall ? "uninstall" : "install";
        try {
            deliverProgress(callback, "Preparing the GPU acceleration " + verb + "...");
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
            if (preferences == null) {
                Error error = new Error("Failed to access the app preferences; the GPU acceleration "
                    + verb + " cannot proceed.");
                Logger.logErrorExtended(LOG_TAG, error.toString());
                deliverError(callback, error);
                return;
            }

            // Cheap honesty guard: nothing can be installed into (or uninstalled
            // from) a guest that does not exist. The action gate itself stays with
            // the calling UI (T8); this only protects the pipeline.
            if (!DebianInstaller.isInstalled()) {
                Error error = new Error("The Debian rootfs is not installed; the GPU acceleration cannot be "
                    + verb + " — install Debian first.");
                Logger.logErrorExtended(LOG_TAG, error.toString());
                deliverError(callback, error);
                return;
            }

            if (!uninstall) {
                // RF-7: on ANY download failure nothing runs inside the guest
                // (also logged by downloadDebs itself).
                deliverProgress(callback, "Downloading the Mesa .deb packages...");
                Error downloadError = downloadDebs(context);
                if (downloadError != null) {
                    deliverError(callback, downloadError);
                    return;
                }
            }

            String guestCommand = uninstall
                ? GpuGuestCommands.buildUninstallCommand(GpuDebCatalog.PACKAGE_NAMES)
                : GpuGuestCommands.buildInstallCommand(GpuDebCatalog.DEB_FILE_NAMES,
                    GpuDebCatalog.PACKAGE_NAMES);
            String guestWorkingDirectory = uninstall
                ? ProotShellEnvironment.GUEST_HOME
                : getInstallGuestWorkingDirectory();

            deliverProgress(callback,
                "Running apt inside the Debian guest (this may take several minutes)...");
            GuestSessionResult sessionResult = runGuestCommand(context,
                GpuGuestCommands.assembleOneShotGuestCommand(guestCommand),
                guestWorkingDirectory,
                "GPU acceleration " + verb);

            // -1 stands for «unverifiable» (no marker, failed session): it always
            // resolves as a failed transition (see resolveExitCode).
            int exitCode = sessionResult.error == null
                ? resolveExitCode(sessionResult.transcript, sessionResult.sessionExitCode)
                : -1;

            writeGuestLog(context, uninstall, exitCode, sessionResult.transcript, exitCode != 0);

            if (sessionResult.error != null || exitCode != 0) {
                Error error = sessionResult.error != null ? sessionResult.error
                    : new Error("The apt command inside the Debian guest failed for the GPU acceleration "
                        + verb + " with exit code "
                        + (exitCode < 0 ? "(unverified: missing exit marker)" : exitCode) + ".");
                // RF-3/RF-5: never silence, and the preference is NOT changed.
                Logger.logErrorExtended(LOG_TAG, "GPU apt run failed; the \"gpu_solution\" "
                    + "preference is NOT changed (RF-3/RF-5):\n" + error
                    + "\nTranscript tail:\n" + transcriptTail(sessionResult.transcript));
                Logger.showToast(context, "GPU acceleration " + verb + " failed ("
                    + (exitCode < 0 ? "session error" : "apt exit code " + exitCode)
                    + "). See " + TermuxConstants.DEBIAN_GPU_LOG_FILE_PATH, true);
                deliverError(callback, error);
                return;
            }

            GpuDetectionLibrary.Solution newSolution = GpuGuestCommands.computeNewSolution(
                preferences.getGpuSolution(), 0, uninstall);
            preferences.setGpuSolution(newSolution);
            Logger.logInfo(LOG_TAG, "GPU acceleration " + verb + " completed; \"gpu_solution\" is now "
                + newSolution + " (transcript in \"" + TermuxConstants.DEBIAN_GPU_LOG_FILE_PATH + "\").");
            Logger.showToast(context, "GPU acceleration " + verb + " completed successfully.", false);
            deliverSuccess(callback, newSolution);
        } catch (Throwable throwable) {
            Error error = new Error("The GPU acceleration " + verb + " failed with an exception.", throwable);
            Logger.logErrorExtended(LOG_TAG, error.toString());
            deliverError(callback, error);
        }
    }

    /**
     * Run a ONE-SHOT command inside the Debian guest through the same session path
     * {@code TermuxService.createTermuxSession()} uses (plan): an
     * {@link ExecutionCommand} of runner {@link Runner#TERMINAL_SESSION} whose
     * executable/arguments are the {@link ProotShellEnvironment#buildProotCommand}
     * array ({@code /bin/bash --login -c <assembledCommand>}, linkfix included as
     * in any other session), executed with {@link TermuxSession#execute} using a
     * NO-UI client pair (the session is never registered in the service, so it
     * does not appear in the tabs and does not disturb the spec 010 overlay
     * bookkeeping) and {@code setStdoutOnExit=true} to capture the transcript.
     *
     * <p>Blocking with a bounded wait is intentional: the caller runs on the
     * operation's background thread. On timeout or interruption the session is
     * SIGKILLed and the {@link Error} composes the reason.</p>
     *
     * @param context               The application context (session lifecycle).
     * @param assembledGuestCommand The guest chain WITH the exit marker already
     *                              appended ({@link GpuGuestCommands#assembleOneShotGuestCommand}).
     * @param guestWorkingDirectory The guest {@code -w} directory (the deb staging
     *                              dir for installs, {@value ProotShellEnvironment#GUEST_HOME}
     *                              for uninstalls).
     * @param commandLabel          Human-readable label for logs and error
     *                              strings (e.g. "GPU acceleration install" or
     *                              "GPU mesa version query (dpkg-query)").
     * @return The captured transcript and raw session exit status, plus the
     *         {@link Error} when the session itself failed to run; never thrown.
     */
    @NonNull
    static GuestSessionResult runGuestCommand(@NonNull Context context,
                                              @NonNull String assembledGuestCommand,
                                              @NonNull String guestWorkingDirectory,
                                              @NonNull String commandLabel) {
        GuestSessionResult result = new GuestSessionResult();

        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            null, null, null, ProotShellEnvironment.getNativeWorkingDirectoryPath(),
            Runner.TERMINAL_SESSION.getName(), false);
        executionCommand.commandLabel = commandLabel;
        executionCommand.commandDescription = "One-shot apt run inside the Debian guest (spec 011 RF-3/RF-5)";
        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows =
            TermuxAppSharedProperties.getProperties().getTerminalTranscriptRows();

        // Same transformation as TermuxService.createTermuxSession(): the pty
        // process execs proot, which re-establishes the guest cwd via "-w"; the
        // guest command arrives through the bash argv ("bash --login -c ...").
        String[] prootCommand = ProotShellEnvironment.buildProotCommand(guestWorkingDirectory,
            new String[]{"-c", assembledGuestCommand}, ProotShellEnvironment.isLinkfixInstalledOnHost(),
            null);
        executionCommand.executable = prootCommand[0];
        executionCommand.arguments = Arrays.copyOfRange(prootCommand, 1, prootCommand.length);

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        CountDownLatch latch = new CountDownLatch(1);
        GpuOneShotSessionClient sessionClient = new GpuOneShotSessionClient(latch);
        GpuOneShotTerminalSessionClient terminalSessionClient =
            new GpuOneShotTerminalSessionClient();

        TermuxSession termuxSession = TermuxSession.execute(context, executionCommand,
            terminalSessionClient, sessionClient, new ProotShellEnvironment(), null, true);
        if (termuxSession == null) {
            result.error = new Error("Failed to start the \"" + commandLabel
                + "\" guest session inside the Debian guest.");
            Logger.logErrorExtended(LOG_TAG, "Failed to execute the one-shot guest session:\n"
                + executionCommand.toString());
            return result;
        }
        // Wire the wrapper AFTER execute() returns; onSessionFinished backstops
        // for the (theoretical) microsecond window before the assignment.
        terminalSessionClient.mTermuxSession = termuxSession;

        try {
            if (!latch.await(GUEST_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // On timeout, preserve whatever output exists for the log, then kill.
                result.transcript = ShellUtils.getTerminalSessionTranscriptText(
                    termuxSession.getTerminalSession(), true, false);
                termuxSession.killIfExecuting(context, false);
                result.error = new Error("The \"" + commandLabel
                    + "\" command inside the Debian guest did not finish within 20 minutes "
                    + "and was killed (SIGKILL).");
                Logger.logErrorExtended(LOG_TAG, "Guest session timed out:\n" + result.error);
                return result;
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            termuxSession.killIfExecuting(context, false);
            result.error = new Error("The \"" + commandLabel
                + "\" guest operation was interrupted while waiting for the guest session.");
            Logger.logErrorExtended(LOG_TAG, "Guest session interrupted:\n" + result.error);
            return result;
        }

        result.transcript = sessionClient.mTranscript;
        result.sessionExitCode = sessionClient.mSessionExitCode;
        if (sessionClient.mFailedState) {
            // The session state machine failed (process start issues, plugin-side
            // SIGKILL bookkeeping, ...). The raw status may carry the real code.
            result.error = new Error("The \"" + commandLabel
                + "\" guest session ended in a failed state (raw session exit status: "
                + result.sessionExitCode + ").");
            Logger.logErrorExtended(LOG_TAG, "Guest session failed state:\n" + result.error);
        }
        return result;
    }

    /**
     * Resolve the guest exit code after a one-shot run (T6 key decision, plan
     * «Interfaz del runner de comandos en el invitado»): the transcript marker
     * wins, because the session pid is proot itself and can exit 0 even when the
     * guest chain failed (the trailing {@code echo} runs unconditionally).
     *
     * <p>Without a marker: a non-zero raw session status is honest evidence of an
     * abort/kill and is forwarded; exit 0 without a marker resolves as
     * {@code -1} («unverifiable») — NEVER as success, so the preference can only
     * ever be promoted by a verified exit 0 (RF-3).</p>
     *
     * @param transcript      The captured session transcript, may be {@code null}.
     * @param sessionExitCode The raw session (proot pid) exit status captured by
     *                        {@code TermuxSession.finish()}.
     * @return The resolved exit code; {@code -1} when unverifiable.
     */
    static int resolveExitCode(@Nullable String transcript, int sessionExitCode) {
        int marker = GpuGuestCommands.parseExitMarker(transcript);
        if (marker >= 0)
            return marker;
        return sessionExitCode != 0 ? sessionExitCode : -1;
    }

    /**
     * Guest working directory of the install operation: the deb staging dir is
     * staged UNDER the rootfs ({@code files/debian/gpu-debs}), so it is mapped
     * directly into the guest tree and {@code ./deb} arguments resolve without
     * extra binds (see {@link ProotShellEnvironment#hostPathToGuestPath}).
     *
     * @return The guest cwd, e.g. {@code /gpu-debs}.
     */
    @NonNull
    static String getInstallGuestWorkingDirectory() {
        return ProotShellEnvironment.hostPathToGuestPath(TermuxConstants.DEBIAN_GPU_DEBS_DIR_PATH);
    }

    /** Guest cwd of the uninstall operation: plain home, no local files involved. */
    @NonNull
    static String getUninstallGuestWorkingDirectory() {
        return ProotShellEnvironment.GUEST_HOME;
    }

    /**
     * Best-effort persistence of the one-shot session transcript (timestamps,
     * resolved exit code) to {@link TermuxConstants#DEBIAN_GPU_LOG_FILE_PATH} for
     * debugging; write failures are logged, never thrown, and never change the
     * operation's outcome.
     *
     * @param context     The application context.
     * @param uninstall   Whether the run was an uninstall (for the header).
     * @param exitCode    The resolved exit code ({@code -1} = unverifiable, for the header).
     * @param transcript  The captured transcript, may be {@code null}.
     * @param failure     Whether the resolved outcome was a failure (for the header).
     */
    private static void writeGuestLog(@NonNull Context context, boolean uninstall, int exitCode,
                                      @Nullable String transcript, boolean failure) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String header = "GPU acceleration " + (uninstall ? "uninstall" : "install")
            + (failure ? " FAILED" : " succeeded")
            + " with " + (exitCode < 0 ? "an UNVERIFIED exit code (missing marker)" : "exit code " + exitCode)
            + " at " + dateFormat.format(new Date()) + ".\n";
        String body = transcript == null
            ? "(no transcript captured; the session may have been killed abruptly)"
            : transcript;
        Error logError = FileUtils.writeTextToFile("gpu-install log",
            TermuxConstants.DEBIAN_GPU_LOG_FILE_PATH, StandardCharsets.UTF_8,
            header + "\n--- session transcript ---\n" + body + "\n", false);
        if (logError != null)
            Logger.logErrorExtended(LOG_TAG, "Failed to write the GPU install log:\n" + logError);
    }

    /**
     * Short tail of a transcript for error log lines; {@code null}-safe.
     *
     * @param transcript The transcript, may be {@code null}.
     * @return The last ~500 characters, or a placeholder.
     */
    @NonNull
    private static String transcriptTail(@Nullable String transcript) {
        if (transcript == null)
            return "(no transcript captured)";
        return transcript.length() <= 500 ? transcript
            : "...(truncated)..." + transcript.substring(transcript.length() - 500);
    }

    /** Captured outcome of a one-shot guest session (see {@link #runGuestCommand}). */
    static final class GuestSessionResult {

        /** The captured transcript, {@code null} when nothing was captured. */
        String transcript;
        /** The raw session (proot pid) exit status; {@code -1} when not captured. */
        int sessionExitCode = -1;
        /** Whether the session state machine failed (kill/start issues). */
        boolean sessionFailedState;
        /** The session-level error (start failure, timeout, interruption, failed state). */
        Error error;
    }

    /**
     * {@link TermuxSession.TermuxSessionClient} of the one-shot session: captures
     * the result data populated by {@code TermuxSession.finish()} and releases the
     * caller's latch. Deliberately NOT {@code TermuxService}: the service's own
     * client would mis-bookkeep the spec 010 overlay counter for a session it
     * never issued.
     */
    static final class GpuOneShotSessionClient implements TermuxSession.TermuxSessionClient {

        private final CountDownLatch mLatch;
        volatile String mTranscript;
        volatile int mSessionExitCode = -1;
        volatile boolean mFailedState;

        GpuOneShotSessionClient(@NonNull CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onTermuxSessionExited(TermuxSession termuxSession) {
            try {
                if (termuxSession == null)
                    return;
                ExecutionCommand executionCommand = termuxSession.getExecutionCommand();
                if (executionCommand == null)
                    return;
                if (executionCommand.resultData != null) {
                    mTranscript = executionCommand.resultData.stdout == null
                        ? "" : executionCommand.resultData.stdout.toString();
                    mSessionExitCode = executionCommand.resultData.exitCode == null
                        ? -1 : executionCommand.resultData.exitCode;
                }
                mFailedState = executionCommand.isStateFailed();
            } finally {
                mLatch.countDown();
            }
        }
    }

    /**
     * {@link TerminalSessionClient} of the one-shot session: on process exit it
     * routes {@code onSessionFinished} into {@code TermuxSession.finish()}, which
     * populates the transcript/exit data and fires the session client above. A
     * no-UI {@link TermuxTerminalSessionClientBase} (all other callbacks are no-ops).
     */
    static final class GpuOneShotTerminalSessionClient extends TermuxTerminalSessionClientBase {

        /** The wrapper whose {@code finish()} drives the result capture. */
        volatile TermuxSession mTermuxSession;

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            TermuxSession termuxSession = mTermuxSession;
            // execute() returns BEFORE asynchrony could end the session, but wait
            // for the (theoretical) race backstop instead of dropping a result.
            long waited = 0;
            while (termuxSession == null && waited < 5000) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
                waited += 50;
                termuxSession = mTermuxSession;
            }
            if (termuxSession == null)
                Logger.logErrorExtended(LOG_TAG, "The one-shot GPU guest session finished while its "
                    + "result capture was not wired; the outcome cannot be resolved.");
            else
                termuxSession.finish();
        }
    }

    /**
     * Deliver a main-thread progress update (weak contract: failures to deliver
     * fall back to inline delivery on the worker thread, e.g. under tests).
     */
    private static void deliverProgress(@Nullable final GpuAccelerationCallback callback,
                                        final String message) {
        if (callback == null) return;
        postOnMainThread(() -> callback.onProgress(message));
    }

    /** Main-thread success delivery. */
    private static void deliverSuccess(@Nullable final GpuAccelerationCallback callback,
                                       final GpuDetectionLibrary.Solution newSolution) {
        if (callback == null) return;
        postOnMainThread(() -> callback.onSuccess(newSolution));
    }

    /** Main-thread error delivery. */
    private static void deliverError(@Nullable final GpuAccelerationCallback callback,
                                     final Error error) {
        if (callback == null) return;
        postOnMainThread(() -> callback.onError(error));
    }

    /**
     * Post on the main looper; when no looper exists (plain JVM tests), deliver
     * inline so the contract never deadlocks the pipeline.
     *
     * @param runnable The delivery work.
     */
    private static void postOnMainThread(@NonNull Runnable runnable) {
        try {
            new Handler(Looper.getMainLooper()).post(runnable);
        } catch (RuntimeException e) {
            runnable.run();
        }
    }

}
