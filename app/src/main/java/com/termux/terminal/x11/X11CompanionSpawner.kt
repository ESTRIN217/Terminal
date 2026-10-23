package com.termux.terminal.x11

import android.content.Context
import com.termux.shared.errors.Errno
import com.termux.shared.errors.Error
import com.termux.shared.logger.Logger
import com.termux.shared.termux.x11.TermuxX11GuestLoaderLibrary

/**
 * Starts the companion X server as a direct child of the app process
 * (spec 014, enmienda B). This is the same path the upstream Termux app uses:
 * an `untrusted_app` uid may exec `/system/bin/app_process` outside of proot,
 * where the APEX linker finds its native `/linkerconfig` namespaces.
 *
 * The child is fire-and-forget: it announces the display to the app via the
 * `ACTION_X11_START` broadcast ([X11BroadcastReceiver]) right after startup.
 * Re-spawning while a server is live is harmless — `CmdEntryPoint` re-announces
 * an existing server instead of fighting for the socket.
 */
object X11CompanionSpawner {

    private const val LOG_TAG = "X11CompanionSpawner"

    /**
     * Spawn the companion process for a display.
     *
     * @param context app context, must not be null
     * @param displayArg X display like ":0" or "1"
     * @param tag optional document tag
     * @return null when the process was started, otherwise the [Error]
     */
    fun spawn(context: Context, displayArg: String, tag: String?): Error? {
        if (context == null)
            return Errno.ERRNO_FAILED.getError("context must not be null", "spawn")

        val args = X11CompanionCommand.buildSpawnArgs(displayArg, tag)
            ?: return Errno.ERRNO_FAILED.getError(
                "Invalid X11 companion arguments: display=\"$displayArg\" tag=\"$tag\"")

        val classpath = context.applicationInfo?.sourceDir
        if (classpath.isNullOrEmpty())
            return Errno.ERRNO_FAILED.getError("Could not resolve the app APK path (applicationInfo.sourceDir)")

        return try {
            val process = ProcessBuilder(args)
                .apply {
                    environment()["CLASSPATH"] = classpath
                    // Legacy packaging extracts the .so to nativeLibraryDir on
                    // install; the child (whose createContext() is the system
                    // context) must load it from there, not from the APK.
                    environment()[TermuxX11GuestLoaderLibrary.ENV_X11_LIBDIR] =
                        context.applicationInfo.nativeLibraryDir ?: ""
                    redirectErrorStream(true)
                }
                .start()
            // Drain the pipe on a daemon thread so a chatty child can never
            // block on a full stdout buffer.
            val drain = process.inputStream
            Thread({
                try {
                    val buffer = CharArray(2048)
                    val reader = java.io.BufferedReader(
                        java.io.InputStreamReader(drain, Charsets.UTF_8))
                    while (reader.read(buffer) >= 0) { /* discard */ }
                } catch (_: Exception) {
                    // the child exited or the pipe broke; nothing to do
                }
            }, "x11-companion-drain").also { it.isDaemon = true }.start()
            Logger.logDebug(LOG_TAG, "Spawned termux-x11 companion: ${args.joinToString(" ")}")
            null
        } catch (e: Exception) {
            val error = Errno.ERRNO_FAILED.getError(e,
                "Failed to start the termux-x11 companion (permissions?): " + e.message)
            Logger.logErrorExtended(LOG_TAG, "Failed\n" + error)
            error
        }
    }
}
