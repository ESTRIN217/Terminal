package com.estrin217.filemanager.compose

/**
 * What the "Run in terminal" action should actually launch for one file
 * (spec 023, RF-2..RF-4). Executables map to [Direct]; non-executable scripts
 * map to [Interpreter] when their extension has a known interpreter available
 * in the rootfs; everything else is [NotExecutable] (the app notifies and
 * never opens a session, RF-3 — no silent `chmod`, constitution 5).
 */
sealed interface RunPlan {
    /** Launch the file as-is: an already escaped `./<name>` command. */
    data class Direct(val command: String) : RunPlan

    /** Launch through a rootfs interpreter: `<interpreter> <name>`, escaped. */
    data class Interpreter(val interpreter: String, val command: String) : RunPlan

    /** No executable bit and no available interpreter for the extension. */
    data object NotExecutable : RunPlan
}

/**
 * Pure decision policy for the app's "Run in terminal" flow (spec 023):
 * the *availability* inputs live where they can be measured — `canExecute`
 * on the host file and the rootfs `usr/bin` binaries the app probes — so the
 * decision itself stays reproducible in JUnit (plan decision 4).
 *
 * Extension → interpreter mapping (case-insensitive): `.sh`/`.bash` → `bash`,
 * `.py` → `python3`. Only the listed [interpretersAvailable] can be picked;
 * the command embeds the file name shell-escaped with the same `'` →
 * `'\''` rule `openFileInEditor` applies to the guest path.
 */
object RunPolicy {

    private val interpreterByExtension = mapOf(
        "sh" to "bash",
        "bash" to "bash",
        "py" to "python3"
    )

    /** Shell-embeds [raw] so it survives inside a `bash -c` script. */
    private fun shellEscape(raw: String): String =
        raw.replace("'", "'\\''")

    /**
     * Decides what running [name] means.
     *
     * @param name File name (extension probed case-insensitively).
     * @param canExecute Whether the host file carries an exec bit.
     * @param interpretersAvailable Rootfs interpreter binary names the app
     * probed (e.g. `{bash, python3}`).
     * @return The [RunPlan] for the session to launch.
     */
    fun decide(name: String, canExecute: Boolean, interpretersAvailable: Set<String>): RunPlan {
        if (canExecute) {
            return RunPlan.Direct("./" + shellEscape(name))
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        val interpreter = interpreterByExtension[extension]
        if (interpreter != null && interpreter in interpretersAvailable) {
            val command = interpreter + " " + shellEscape(name)
            return RunPlan.Interpreter(interpreter, command)
        }
        return RunPlan.NotExecutable
    }
}
