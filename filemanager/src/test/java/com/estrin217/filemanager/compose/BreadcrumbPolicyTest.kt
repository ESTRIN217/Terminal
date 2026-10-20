package com.estrin217.filemanager.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for [BreadcrumbPolicy] (spec 008, RF-2): the breadcrumb train is
 * one segment per ancestor, with friendly labels ("Root" for `/`, "Home"
 * for the Debian guest home root and its descendants start at Home), the
 * last segment being the current folder.
 */
class BreadcrumbPolicyTest {

    private fun crumb(label: String, path: String) = BreadcrumbPolicy.Crumb(label, path)

    @Test
    fun root_isSingleRootSegment() {
        assertEquals(listOf(crumb("Root", "/")), BreadcrumbPolicy.crumbs("/", null))
    }

    @Test
    fun homeRoot_isSingleHomeSegment() {
        assertEquals(listOf(crumb("Home", "/home/deb")), BreadcrumbPolicy.crumbs("/home/deb", "/home/deb"))
    }

    @Test
    fun homeChild_startsAtHome() {
        assertEquals(
            listOf(
                crumb("Home", "/home/deb"),
                crumb("sub", "/home/deb/sub")
            ),
            BreadcrumbPolicy.crumbs("/home/deb/sub", "/home/deb")
        )
    }

    @Test
    fun detachedPath_startsAtRoot() {
        assertEquals(
            listOf(
                crumb("Root", "/"),
                crumb("sdcard", "/sdcard")
            ),
            BreadcrumbPolicy.crumbs("/sdcard", "/home/deb")
        )
    }

    @Test
    fun deepPath_hasAllSegments() {
        assertEquals(
            listOf(
                crumb("Root", "/"),
                crumb("storage", "/storage"),
                crumb("emulated", "/storage/emulated"),
                crumb("0", "/storage/emulated/0")
            ),
            BreadcrumbPolicy.crumbs("/storage/emulated/0", "/home/deb")
        )
    }

    @Test
    fun blankPath_isEmpty() {
        assertEquals(emptyList<BreadcrumbPolicy.Crumb>(), BreadcrumbPolicy.crumbs("", null))
        assertEquals(emptyList<BreadcrumbPolicy.Crumb>(), BreadcrumbPolicy.crumbs("   ", "/home/deb"))
    }

    @Test
    fun trailingSlash_isNormalized() {
        assertEquals(
            listOf(
                crumb("Root", "/"),
                crumb("home", "/home"),
                crumb("sub", "/home/sub")
            ),
            BreadcrumbPolicy.crumbs("/home/sub/", null)
        )
    }

    @Test
    fun pathAboveHome_hasFullChain() {
        assertEquals(
            listOf(
                crumb("Root", "/"),
                crumb("home", "/home")
            ),
            BreadcrumbPolicy.crumbs("/home", "/home/deb")
        )
    }
}
