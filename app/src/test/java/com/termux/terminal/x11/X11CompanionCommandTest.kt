package com.termux.terminal.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** JUnit tests for the pure companion launch command builder (spec 014 RF-8). */
class X11CompanionCommandTest {

    private val appId = "com.estrin217.terminal"

    // ---- guest loader (enmienda A) ----

    @Test
    fun buildsGuestCommandForDefaultDisplay() {
        assertEquals("termux-x11 :0", X11CompanionCommand.buildGuestCommand(":0", null))
    }

    @Test
    fun buildsGuestCommandWithTag() {
        assertEquals("termux-x11 :1 -tag main", X11CompanionCommand.buildGuestCommand(":1", "main"))
    }

    @Test
    fun buildsGuestCommandWithBareDisplayAndBlankTag() {
        assertEquals("termux-x11 :3", X11CompanionCommand.buildGuestCommand("3", " "))
    }

    @Test
    fun guestCommandRejectsInvalidInput() {
        assertNull(X11CompanionCommand.buildGuestCommand("bad", null))
        assertNull(X11CompanionCommand.buildGuestCommand(":0", "with spaces"))
        assertNull(X11CompanionCommand.buildGuestCommand(":0", "-flag"))
    }

    @Test
    fun choosesGuestCommandWhenWrapperExists() {
        assertEquals(
            "termux-x11 :0 -tag main",
            X11CompanionCommand.chooseLaunchCommand(true, appId, ":0", "main")
        )
    }

    @Test
    fun choosesOneLinerFallbackWithoutWrapper() {
        assertNotNull(X11CompanionCommand.chooseLaunchCommand(false, appId, ":0", null))
        // Fallback keeps the absolute app_process one-liner.
        assertEquals(
            X11CompanionCommand.buildLaunchCommand(appId, ":0", null),
            X11CompanionCommand.chooseLaunchCommand(false, appId, ":0", null)
        )
    }

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
    fun guestSelectionIgnoresApplicationId() {
        assertEquals(
            "termux-x11 :0",
            X11CompanionCommand.chooseLaunchCommand(true, "", ":0", null)
        )
    }

    @Test
    fun buildsCommandForDefaultDisplay() {
        assertEquals(
            "CLASSPATH=\$(/system/bin/pm path --user 0 $appId | head -1 | cut -d: -f2) " +
                "/system/bin/app_process / --nice-name=termux-x11 " +
                "com.termux.x11.CmdEntryPoint :0",
            X11CompanionCommand.buildLaunchCommand(appId, ":0", null)
        )
    }

    @Test
    fun buildsCommandWithTag() {
        assertEquals(
            "CLASSPATH=\$(/system/bin/pm path --user 0 $appId | head -1 | cut -d: -f2) " +
                "/system/bin/app_process / --nice-name=termux-x11 " +
                "com.termux.x11.CmdEntryPoint :1 -tag main",
            X11CompanionCommand.buildLaunchCommand(appId, "1", "main")
        )
    }

    @Test
    fun addsColonToBareDisplay() {
        assertEquals(
            "CLASSPATH=\$(/system/bin/pm path --user 0 $appId | head -1 | cut -d: -f2) " +
                "/system/bin/app_process / --nice-name=termux-x11 " +
                "com.termux.x11.CmdEntryPoint :1",
            X11CompanionCommand.buildLaunchCommand(appId, "1", null)
        )
    }

    @Test
    fun acceptsFractionalDisplay() {
        assertNotNull(X11CompanionCommand.buildLaunchCommand(appId, ":1.5", null))
        assertNotNull(X11CompanionCommand.buildLaunchCommand(appId, "1.0", null))
    }

    @Test
    fun treatsBlankTagAsNoTag() {
        assertEquals(
            "CLASSPATH=\$(/system/bin/pm path --user 0 $appId | head -1 | cut -d: -f2) " +
                "/system/bin/app_process / --nice-name=termux-x11 " +
                "com.termux.x11.CmdEntryPoint :0",
            X11CompanionCommand.buildLaunchCommand(appId, ":0", "")
        )
    }

    @Test
    fun rejectsMalformedDisplays() {
        for (bad in listOf("", ":", ":", "-1", "x", ":9999x", ":1..2", ":1; rm -rf"))
            assertNull("display \"$bad\" should be rejected",
                X11CompanionCommand.buildLaunchCommand(appId, bad, null))
    }

    @Test
    fun rejectsBlankApplicationId() {
        assertNull(X11CompanionCommand.buildLaunchCommand("", ":0", null))
        assertNull(X11CompanionCommand.buildLaunchCommand("   ", ":0", null))
    }

    @Test
    fun rejectsTagWithSpaces() {
        assertNull(X11CompanionCommand.buildLaunchCommand(appId, ":0", "main session"))
        assertNull(X11CompanionCommand.buildLaunchCommand(appId, ":0", "-quote"))
    }
}
