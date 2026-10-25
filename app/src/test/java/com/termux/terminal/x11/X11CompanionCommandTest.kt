package com.termux.terminal.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JUnit tests for the pure companion spawn helpers (spec 014 RF-8/enmienda B;
 * spec 018 Enmienda A removed the paste-ready command flow and the guest
 * wrapper — those builders live no more).
 */
class X11CompanionCommandTest {

    // ---- app-side spawn (enmienda B) ----

    @Test
    fun buildsSpawnArgsForDefaultDisplay() {
        assertEquals(
            listOf(
                "/system/bin/app_process", "/",
                "--nice-name=termux-x11", "com.termux.x11.CmdEntryPoint", ":0"
            ),
            X11CompanionCommand.buildSpawnArgs(":0", null)
        )
    }

    @Test
    fun buildsSpawnArgsWithTag() {
        assertEquals(
            listOf(
                "/system/bin/app_process", "/",
                "--nice-name=termux-x11", "com.termux.x11.CmdEntryPoint", ":1", "-tag", "main"
            ),
            X11CompanionCommand.buildSpawnArgs(":1", "main")
        )
    }

    @Test
    fun spawnArgsRejectInvalidInput() {
        assertNull(X11CompanionCommand.buildSpawnArgs("bad", null))
        assertNull(X11CompanionCommand.buildSpawnArgs(":0", "with spaces"))
        assertNull(X11CompanionCommand.buildSpawnArgs(":0", "-flag"))
    }

    @Test
    fun companionEnvSetsRootfsTmpdir() {
        assertEquals(
            mapOf("TMPDIR" to "/data/data/com.estrin217.terminal/files/debian/tmp"),
            X11CompanionCommand.buildCompanionEnv("/data/data/com.estrin217.terminal/files/debian/tmp")
        )
    }

    @Test
    fun companionEnvSkipsNullOrBlankTmpdir() {
        assertEquals(emptyMap<String, String>(), X11CompanionCommand.buildCompanionEnv(null))
        assertEquals(emptyMap<String, String>(), X11CompanionCommand.buildCompanionEnv("  "))
    }

    @Test
    fun normalizeDisplayAddsColon() {
        assertEquals(":0", X11CompanionCommand.normalizeDisplay("0"))
        assertEquals(":1", X11CompanionCommand.normalizeDisplay(":1"))
    }

    @Test
    fun normalizeDisplayRejectsMalformed() {
        assertNull(X11CompanionCommand.normalizeDisplay(""))
        assertNull(X11CompanionCommand.normalizeDisplay("x"))
        assertNull(X11CompanionCommand.normalizeDisplay(":9999y"))
    }
}
