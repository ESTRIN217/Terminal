package com.termux.terminal.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** JUnit tests for the pure companion launch command builder (spec 014 RF-8). */
class X11CompanionCommandTest {

    private val appId = "com.estrin217.terminal"

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
