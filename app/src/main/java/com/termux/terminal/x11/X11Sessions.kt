package com.termux.terminal.x11

/**
 * Pure helpers naming and routing X11 session tabs (spec 014 RF-2/RF-4).
 *
 * Deterministic and UI-free so they run in plain JUnit (see X11SessionsTest).
 */
object X11Sessions {

    /**
     * Computes the display name for a new X11 tab, mirroring the file manager rule
     * (first tab keeps the base name, later ones get a suffix "2", "3"...).
     *
     * @param existingCount number of X11 tabs already present
     * @param baseName base display name
     */
    fun nextSessionName(existingCount: Int, baseName: String): String =
        if (existingCount == 0) baseName else "$baseName ${existingCount + 1}"

    /**
     * Tags route companion servers to tabs: an untagged server ("") belongs to the
     * first untagged tab and a named tag to its exact match.
     *
     * @param sessions (id, tag) pairs of the live X11 tabs
     * @param tag incoming tag ("" when the companion runs untitled)
     * @return id of the receiving tab, or null to leave the binder pending in the bus
     */
    fun findSessionIdByTag(sessions: List<Pair<String, String>>, tag: String): String? {
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { it.second == tag }?.first
            ?: if (tag.isEmpty()) sessions.firstOrNull { it.second.isEmpty() }?.first else null
    }
}
