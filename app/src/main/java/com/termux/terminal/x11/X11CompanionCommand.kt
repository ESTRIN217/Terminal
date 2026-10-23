package com.termux.terminal.x11

import com.termux.shared.termux.TermuxConstants

/**
 * Pure helpers to build the companion X server launch command shown to the user in
 * the "no server" state of the X11 tabs (spec 014 RF-8).
 *
 * The companion process cannot be spawned from the app directly: the X server
 * needs `app_process` with the APK itself on the classpath (same UID/process
 * family), so the tab offers a command to paste in a terminal session instead.
 *
 * Deterministic and UI-free so it runs in plain JUnit (see X11CompanionCommandTest).
 */
object X11CompanionCommand {

    /** Display argument: optional colon, 1-4 digits, optional fractional `.digits`. */
    private val DISPLAY_REGEX = Regex("^:?([0-9]{1,4})(\\.[0-9]{1,4})?$")

    /** Tags are single shell-safe tokens; spaces would break the paste-ready command. */
    private val TAG_REGEX = Regex("^[A-Za-z0-9._:-]+$")

    /** Entry-point class shipped by the lorie library (independent of the app id). */
    private const val CMD_ENTRY_POINT_CLASS = "com.termux.x11.CmdEntryPoint"

    /** Fixed prefix of the launcher command. */
    private const val APP_PROCESS = "/system/bin/app_process /"

    /**
     * Builds the shell command that starts the companion X server.
     *
     * @param applicationId installed package id (resolved by the caller)
     * @param displayArg X display like ":0", "1" (colon is added) or ":1.5"
     * @param tag optional document tag; "" or blank means no `-tag` flag
     * @return paste-ready command, or null when any input is invalid
     */
    fun buildLaunchCommand(applicationId: String, displayArg: String, tag: String?): String? {
        if (applicationId.isBlank()) return null
        val display = normalizeDisplay(displayArg) ?: return null
        val trimmedTag = tag?.trim().orEmpty()
        // A leading '-' would read as another flag in the paste-ready command.
        if (trimmedTag.isNotEmpty() && (trimmedTag.startsWith("-") || !TAG_REGEX.matches(trimmedTag))) return null

        val tagPart = if (trimmedTag.isEmpty()) "" else " -tag $trimmedTag"
        val classpath = "\$(pm path --user 0 $applicationId | head -1 | cut -d: -f2)"
        val niceName = TermuxConstants.TERMUX_X11.X11_COMPANION_PROCESS_NAME
        return "CLASSPATH=$classpath $APP_PROCESS --nice-name=$niceName $CMD_ENTRY_POINT_CLASS $display$tagPart"
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
