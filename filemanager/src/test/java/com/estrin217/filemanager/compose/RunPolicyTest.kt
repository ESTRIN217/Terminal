package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 023, RF-2..RF-4: the command the "Run in terminal" action launches.
 * Executables run directly; text scripts run through the interpreter their
 * extension maps to (when the rootfs ships it); anything else is refused.
 * The policy also owns the shell escaping of the file name, so the command
 * can be embedded verbatim in the session's `bash -c` script.
 */
class RunPolicyTest {

    // Executable files run directly, whatever their extension.

    @Test
    fun `executable runs directly`() {
        assertEquals(
            RunPlan.Direct("./deploy"),
            RunPolicy.decide("deploy", true, emptySet())
        )
    }

    @Test
    fun `executable script runs directly`() {
        assertEquals(
            RunPlan.Direct("./deploy.sh"),
            RunPolicy.decide("deploy.sh", true, setOf("bash"))
        )
    }

    // Shell escaping of the command-embedded name (precedente openFileInEditor).

    @Test
    fun `apostrophes escaped in direct command`() {
        assertEquals(
            RunPlan.Direct("./my'\\''guión.sh"),
            RunPolicy.decide("my'guión.sh", true, emptySet())
        )
    }

    @Test
    fun `apostrophes escaped in interpreter command`() {
        assertEquals(
            RunPlan.Interpreter("bash", "bash my'\\''script.sh"),
            RunPolicy.decide("my'script.sh", false, setOf("bash"))
        )
    }

    // Non-executable scripts map extension → interpreter, gated on availability.

    @Test
    fun `sh falls back to bash`() {
        assertEquals(
            RunPlan.Interpreter("bash", "bash build.sh"),
            RunPolicy.decide("build.sh", false, setOf("bash", "python3"))
        )
    }

    @Test
    fun `bash extension falls back to bash`() {
        assertEquals(
            RunPlan.Interpreter("bash", "bash setup.bash"),
            RunPolicy.decide("setup.bash", false, setOf("bash"))
        )
    }

    @Test
    fun `py falls back to python3 when available`() {
        assertEquals(
            RunPlan.Interpreter("python3", "python3 toil.py"),
            RunPolicy.decide("toil.py", false, setOf("bash", "python3"))
        )
    }

    @Test
    fun `py without python3 binary refused`() {
        assertEquals(
            RunPlan.NotExecutable,
            RunPolicy.decide("toil.py", false, setOf("bash"))
        )
    }

    @Test
    fun `txt without bit refused`() {
        assertEquals(
            RunPlan.NotExecutable,
            RunPolicy.decide("notes.txt", false, setOf("bash", "python3"))
        )
    }

    @Test
    fun `unknown extension without bit refused`() {
        assertEquals(
            RunPlan.NotExecutable,
            RunPolicy.decide("setup.lost", false, setOf("bash", "python3"))
        )
    }

    @Test
    fun `sh without bash binary refused`() {
        assertEquals(
            RunPlan.NotExecutable,
            RunPolicy.decide("build.sh", false, setOf("python3"))
        )
    }

    // Case-insensitive extension probing.

    @Test
    fun `uppercase sh falls back to bash`() {
        assertEquals(
            RunPlan.Interpreter("bash", "bash SETUP.SH"),
            RunPolicy.decide("SETUP.SH", false, setOf("bash"))
        )
    }
}
