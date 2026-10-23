package com.termux.terminal.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JUnit tests for the pure X11 session model helpers (spec 014 RF-2/RF-4). */
class X11SessionsTest {

    @Test
    fun firstSessionUsesBaseName() {
        assertEquals("X11", X11Sessions.nextSessionName(0, "X11"))
    }

    @Test
    fun numberedNamesHaveNoCollision() {
        assertEquals("X11 2", X11Sessions.nextSessionName(1, "X11"))
        assertEquals("X11 3", X11Sessions.nextSessionName(2, "X11"))
    }

    @Test
    fun emptyTagRoutesToFirstUntaggedSession() {
        val sessions = listOf("a" to "", "b" to "main")
        assertEquals("a", X11Sessions.findSessionIdByTag(sessions, ""))
    }

    @Test
    fun namedTagRoutesToExactMatch() {
        val sessions = listOf("a" to "", "b" to "main")
        assertEquals("b", X11Sessions.findSessionIdByTag(sessions, "main"))
    }

    @Test
    fun unknownTagLeavesBinderPending() {
        val sessions = listOf("a" to "")
        assertNull(X11Sessions.findSessionIdByTag(sessions, "main"))
    }

    @Test
    fun emptySessionListAlwaysPending() {
        assertNull(X11Sessions.findSessionIdByTag(emptyList(), ""))
    }
}
