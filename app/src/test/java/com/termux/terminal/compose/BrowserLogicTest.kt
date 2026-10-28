package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit tests for the pure browser tab logic (spec 022, RF-2/RF-4/RF-5/RF-7). */
class BrowserLogicTest {

    // linkRoute (RF-4/RF-5/RF-7)

    @Test
    fun httpAndHttpsRouteInternalWhenEnabled() {
        assertEquals(
            BrowserLogic.LinkRoute.INTERNAL_TAB,
            BrowserLogic.routeLink("https://example.com", internalEnabled = true)
        )
        assertEquals(
            BrowserLogic.LinkRoute.INTERNAL_TAB,
            BrowserLogic.routeLink("http://example.com", internalEnabled = true)
        )
    }

    @Test
    fun httpAndHttpsRouteExternalWhenDisabled() {
        assertEquals(
            BrowserLogic.LinkRoute.EXTERNAL,
            BrowserLogic.routeLink("https://example.com", internalEnabled = false)
        )
        assertEquals(
            BrowserLogic.LinkRoute.EXTERNAL,
            BrowserLogic.routeLink("http://example.com", internalEnabled = false)
        )
    }

    @Test
    fun nonWebSchemesNeverRouteInternal() {
        for (uri in listOf(
            "mailto:root@example.com",
            "tel:+123456789",
            "content://com.android.providers/downloads",
            "file:///home/script.sh",
            "about:blank",
            "intent://example.com",
            "ftp://example.com/x"
        )) {
            assertEquals(uri, BrowserLogic.LinkRoute.EXTERNAL, BrowserLogic.routeLink(uri, true))
            assertEquals(uri, BrowserLogic.LinkRoute.EXTERNAL, BrowserLogic.routeLink(uri, false))
        }
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals(
            BrowserLogic.LinkRoute.INTERNAL_TAB,
            BrowserLogic.routeLink("HTTPS://example.com", true)
        )
        assertEquals(
            BrowserLogic.LinkRoute.EXTERNAL,
            BrowserLogic.routeLink("MAILTO:root@example.com", true)
        )
    }

    @Test
    fun blankOrSchemelessUriRoutesExternal() {
        assertEquals(BrowserLogic.LinkRoute.EXTERNAL, BrowserLogic.routeLink("", true))
        assertEquals(BrowserLogic.LinkRoute.EXTERNAL, BrowserLogic.routeLink("   ", true))
        assertEquals(BrowserLogic.LinkRoute.EXTERNAL, BrowserLogic.routeLink("example.com", true))
    }

    // normalizeUrlInput (RF-2)

    @Test
    fun blankInputNormalizesToEmpty() {
        assertEquals("", BrowserLogic.normalizeUrlInput(""))
        assertEquals("", BrowserLogic.normalizeUrlInput("   "))
    }

    @Test
    fun bareHostGetsHttpsPrefix() {
        assertEquals("https://example.com", BrowserLogic.normalizeUrlInput("example.com"))
        assertEquals(
            "https://example.com/path?q=1",
            BrowserLogic.normalizeUrlInput(" example.com/path?q=1 ")
        )
    }

    @Test
    fun hostWithPortGetsHttpsPrefix() {
        assertEquals("https://localhost:8080", BrowserLogic.normalizeUrlInput("localhost:8080"))
    }

    @Test
    fun urlWithSchemeIsUntouched() {
        assertEquals("http://example.com", BrowserLogic.normalizeUrlInput("http://example.com"))
        assertEquals("https://example.com", BrowserLogic.normalizeUrlInput("HTTPS://example.com"))
        assertEquals("file:///etc/hosts", BrowserLogic.normalizeUrlInput("file:///etc/hosts"))
        assertEquals("about:blank", BrowserLogic.normalizeUrlInput("about:blank"))
    }

    // tabLabel (RF-3)

    @Test
    fun longTitleTruncatesToFortyChars() {
        val title = "x".repeat(64)
        val label = BrowserLogic.tabLabel(title, "https://example.com")
        assertEquals(40, label.length)
        assertEquals("x".repeat(40), label)
    }

    @Test
    fun shortTitlePassesthrough() {
        assertEquals("GitHub", BrowserLogic.tabLabel("GitHub", "https://github.com"))
    }

    @Test
    fun emptyTitleFallsBackToHost() {
        assertEquals("github.com", BrowserLogic.tabLabel("", "https://www.github.com/x/y"))
        assertEquals("example.com", BrowserLogic.tabLabel("   ", "https://example.com/"))
    }

    @Test
    fun emptyTitleWithBadUrlFallsBackToTheUrl() {
        assertEquals("not a url", BrowserLogic.tabLabel("", "not a url"))
    }

    // nextSessionName (RF-1)

    @Test
    fun firstTabUsesBaseName() {
        assertEquals("Navegador", BrowserLogic.nextSessionName(0, "Navegador"))
    }

    @Test
    fun numberedTabsHaveNoCollision() {
        assertEquals("Navegador 2", BrowserLogic.nextSessionName(1, "Navegador"))
        assertEquals("Navegador 3", BrowserLogic.nextSessionName(2, "Navegador"))
    }
}
