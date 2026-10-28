package com.termux.terminal.compose

/**
 * Pure helpers for the internal browser tabs (spec 022).
 *
 * Deterministic and UI-free so they run in plain JUnit (see [BrowserLogicTest] and
 * [BrowserSessionsTest]); the Compose host ([BrowserSessionHost]) and the activity only
 * dispatch. Keeps the RF-4/RF-5/RF-7 link routing decision in one place: the terminal
 * renderers and the More-menu all route through it.
 */
object BrowserLogic {

    /** Where a tapped link goes (spec 022 RF-4/RF-5/RF-7). */
    enum class LinkRoute {
        /** Open, or reuse, an in-app browser tab. */
        INTERNAL_TAB,

        /** The previous behavior: hand the URI to the system (ACTION_VIEW). */
        EXTERNAL
    }

    // RFC 3986 schemes this app recognizes as "already a URI". Anything else before the
    // first colon is host:port-like input (e.g. "localhost:8080") and gets https://.
    private val KNOWN_SCHEMES = setOf(
        "http", "https", "file", "ftp", "about", "intent", "mailto", "tel",
        "geo", "market", "content", "data", "ws", "wss", "urn", "javascript"
    )

    /**
     * Decide whether a link opens in an in-app browser tab or is delegated to the system.
     *
     * Only http/https follow the user setting (RF-4/RF-5); every other scheme — including
     * blank or scheme-less URIs, which terminals never tap by way of the spec 003
     * allowlist anyway — goes external (RF-7).
     *
     * @param uri the URI as printed/tapped in the terminal or chosen in the More-menu
     * @param internalEnabled whether the "open links in-app" preference is on
     * @return the route for the URI
     */
    fun routeLink(uri: String, internalEnabled: Boolean): LinkRoute {
        val scheme = schemeOf(uri)
        return if (scheme in listOf("http", "https") && internalEnabled) {
            LinkRoute.INTERNAL_TAB
        } else {
            LinkRoute.EXTERNAL
        }
    }

    /**
     * Normalize what the user typed in the URL bar into a loadable URL (RF-2).
     *
     * Blank input stays empty (the host ignores the request). Input without a
     * [KNOWN_SCHEMES] prefix carries an implicit `https://`; recognized-scheme input is
     * returned as it was typed.
     *
     * @param input raw text of the URL bar
     * @return a scheme-complete URL, possibly empty
     */
    fun normalizeUrlInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        val scheme = schemeOf(trimmed)
        return when {
            scheme !in KNOWN_SCHEMES -> "https://$trimmed"
            // RFC 3986 §3.1: schemes are case-insensitive; normalize what the user typed.
            else -> scheme + trimmed.substring(trimmed.trim().indexOf(':'))
        }
    }

    /**
     * Tab label for a page (RF-3): the document title when present, truncated to keep the
     * tab bar stable; otherwise the short host of the URL the tab shows.
     *
     * @param pageTitle document title as reported by the engine (may be null/blank)
     * @param pageUrl current URL of the tab
     * @return display label for the tab
     */
    fun tabLabel(pageTitle: String?, pageUrl: String): String = when {
        !pageTitle.isNullOrBlank() -> truncate(pageTitle.trim())
        else -> shortHost(pageUrl)
    }

    /**
     * Display name for a new browser tab, mirroring the file manager/X11 rule (RF-1):
     * the first tab keeps the base name, later ones get a "2", "3"... suffix.
     *
     * @param existingCount number of browser tabs already present
     * @param baseName base display name
     */
    fun nextSessionName(existingCount: Int, baseName: String): String =
        if (existingCount == 0) baseName else "$baseName ${existingCount + 1}"

    /**
     * Lowercased scheme part of {@code uri} ("", "http", "mailto"…). Case-insensitive
     * (RF schemes are, RFC 3986 §3.1).
     */
    private fun schemeOf(uri: String): String {
        val colon = uri.trim().indexOf(':')
        if (colon <= 0) return ""
        return uri.trim().take(colon).lowercase()
    }

    private fun truncate(title: String, max: Int = 40): String =
        if (title.length > max) title.take(max) else title

    private fun shortHost(pageUrl: String): String = try {
        val host = java.net.URI(pageUrl.trim()).host
        if (host.isNullOrEmpty()) pageUrl.trim()
        else host.removePrefix("www.")
    } catch (e: java.net.URISyntaxException) {
        pageUrl.trim()
    }
}
