package com.estrin217.filemanager.compose

/**
 * Pure breadcrumb builder for the file manager's path train (spec 008,
 * RF-2): one segment per ancestor of the current folder, first segment
 * friendly-labelled and last segment non-clickable (the open folder).
 *
 * The chain starts at the folder of highest rank the open folder descends
 * from: the Debian guest home is labelled "Home" and swallows its
 * ancestors, everything else starts at "Root". Shared-storage paths keep
 * their own segments (`/sdcard` → Root > sdcard) because the ViewModel
 * never canonicalizes them.
 */
object BreadcrumbPolicy {

    /** Label of the filesystem root segment. */
    const val ROOT_LABEL = "Root"

    /** Label of the Debian guest home segment. */
    const val HOME_LABEL = "Home"

    /**
     * One clickable (or terminal) breadcrumb step.
     *
     * @param label Friendly display label of the segment.
     * @param path Absolute path the segment navigates to.
     */
    data class Crumb(val label: String, val path: String)

    /**
     * Builds the breadcrumb train for [path].
     *
     * @param path Absolute path of the current folder.
     * @param homePath Absolute path of the Debian guest home, or null when
     *                 the rootfs is not installed (no Home detour then).
     * @return The ancestor chain from the highest friendly root to the
     *         current folder; empty for blank input or a non-absolute path.
     */
    fun crumbs(path: String, homePath: String?): List<Crumb> {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return emptyList()
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return listOf(Crumb(ROOT_LABEL, "/"))

        val home = homePath?.let { normalize(it) }?.takeIf { it != "/" }
        val paths = ArrayList<String>(segments.size + 1)
        paths.add("/")
        var accumulated = ""
        for (segment in segments) {
            accumulated = "$accumulated/$segment"
            paths.add(accumulated)
        }
        if (paths.last() == home) return listOf(Crumb(HOME_LABEL, normalizedOr(paths.last(), trimmed)))
        val homeIndex = home?.let { paths.indexOf(it) } ?: -1
        val start = if (homeIndex > 0) homeIndex else 0
        val crumbs = ArrayList<Crumb>(paths.size - start)
        for (i in start until paths.size) {
            val crumbPath = paths[i]
            val label = when {
                i == 0 -> ROOT_LABEL
                crumbPath == home -> HOME_LABEL
                else -> segments[i - 1]
            }
            crumbs.add(Crumb(label, crumbPath))
        }
        return crumbs
    }

    /**
     * Normalizes an absolute path for chain comparisons: collapses repeated
     * separators and a trailing one, keeping "/" for the root.
     *
     * @param raw The raw path input.
     * @return The normalized path, or null when it is blank or relative.
     */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
        return "/" + trimmed.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }

    /**
     * Prefers the caller's exact spelling of the folder over the rebuilt
     * one when both normalize to the same chain, so `/home/sub/` renders
     * its crumb path as written.
     *
     * @param rebuilt The segment-rebuilt absolute path.
     * @param raw The trimmed original path.
     * @return The rendering path for the terminal crumb.
     */
    private fun normalizedOr(rebuilt: String, raw: String): String = raw
}
