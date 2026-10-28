package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test del nombre de pestaña de navegador numerado (spec 022 RF-1, patrón X11SessionsTest). */
class BrowserSessionsTest {

    @Test
    fun primeraNombraConLaBase() {
        assertEquals("Navegador", BrowserLogic.nextSessionName(0, "Navegador"))
    }

    @Test
    fun numeradasSinColision() {
        assertEquals("Navegador 2", BrowserLogic.nextSessionName(1, "Navegador"))
        assertEquals("Navegador 3", BrowserLogic.nextSessionName(2, "Navegador"))
    }
}
