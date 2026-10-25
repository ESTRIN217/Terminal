package com.termux.terminal.x11

import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.command.environment.ProotShellEnvironment

/**
 * Pure helpers to spawn the companion X server from the app process, wired by
 * [X11CompanionSpawner] (spec 014, enmienda B; spec 018 Enmienda A removed the
 * paste-ready command flow and the guest wrapper).
 *
 * Deterministic and UI-free so it runs in plain JUnit (see X11CompanionCommandTest).
 */
object X11CompanionCommand {

    /** Display argument: optional colon, 1-4 digits, optional fractional `.digits`. */
    private val DISPLAY_REGEX = Regex("^:?([0-9]{1,4})(\\.[0-9]{1,4})?$")

    /** Tags are single shell-safe tokens; spaces would break the spawn argv. */
    private val TAG_REGEX = Regex("^[A-Za-z0-9._:-]+$")

    /** Entry-point class shipped by the lorie library (independent of the app id). */
    private const val CMD_ENTRY_POINT_CLASS = "com.termux.x11.CmdEntryPoint"

    /** Absolute path of the Android app_process binary. */
    private const val APP_PROCESS_BINARY = "/system/bin/app_process"

    /**
     * Builds the argv list to spawn the companion X server directly from the
     * app process (spec 014, enmienda B): same tokens as the paste-ready
     * one-liner, without any shell. The spawning caller must set the
     * `CLASSPATH` env var to the app's own APK path.
     *
     * @param displayArg X display like ":0", "1" (colon is added) or ":1.5"
     * @param tag optional document tag; "" or blank means no `-tag` flag
     * @return spawn argv, or null when any input is invalid
     */
    fun buildSpawnArgs(displayArg: String, tag: String?): List<String>? {
        val display = normalizeDisplay(displayArg) ?: return null
        val trimmedTag = tag?.trim().orEmpty()
        if (trimmedTag.isNotEmpty() && (trimmedTag.startsWith("-") || !TAG_REGEX.matches(trimmedTag))) return null
        val args = mutableListOf(
            APP_PROCESS_BINARY, "/",
            "--nice-name=${TermuxConstants.TERMUX_X11.X11_COMPANION_PROCESS_NAME}",
            CMD_ENTRY_POINT_CLASS, display
        )
        if (trimmedTag.isNotEmpty()) args += listOf("-tag", trimmedTag)
        return args
    }

    /**
     * Builds the extra environment for the spawned companion (spec 014, enmienda B, pure).
     * `TMPDIR` must be the **rootfs** `/tmp`: the X server creates its sockets there
     * (`$TMPDIR/.X11-unix/X*`) so proot guests reach the same socket at `/tmp`, and
     * the companion's chroot-style xkb heuristic then finds the rootfs keymaps.
     *
     * @param tmpDirPath writable tmp dir path (rootfs `/tmp` preferred); null/blank skips
     * @return env entries to merge into the spawn environment (may be empty)
     */
    fun buildCompanionEnv(tmpDirPath: String?): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (!tmpDirPath.isNullOrBlank()) {
            env[ProotShellEnvironment.ENV_TMPDIR] = tmpDirPath
        }
        return env
    }

    /**
     * Normalizes a display argument to the colon form `:N[.M]`.
     * @return normalized display or null when it does not match the allowed shape
     */
    fun normalizeDisplay(displayArg: String): String? {
        val match = DISPLAY_REGEX.matchEntire(displayArg.trim()) ?: return null
        val base = match.groupValues[1]
        val fraction = match.groupValues[2]
        return ":$base$fraction"
    }
}
