package com.estrin217.filemanager.compose

/**
 * A rootfs editor candidate probed by the caller (spec 009, RF-2 / "abrir
 * con"): [available] is `true` when the binary exists and is executable in
 * the installed rootfs (`usr/bin/nano`, `usr/bin/vim`), decided outside the
 * policy so it stays a pure function of the given list.
 *
 * @param name Editor name as shipped in the rootfs ("nano", "vim").
 * @param available Whether the caller can run that editor.
 */
data class EditorCandidate(val name: String, val available: Boolean)

/**
 * Pure policy to pick the rootfs editor for "Open with › Rootfs" (spec 009,
 * RF-2): the host order is honored, "the available one" means the first
 * available candidate (nano when present, vim as the fallback), and no
 * available editor at all blocks the session with the caller's warning.
 */
object EditorPolicy {

    /**
     * Picks the first available candidate in the given order.
     *
     * @param candidates Editors in preference order (nano → vim for the
     * menu; any host-provided order is accepted).
     * @return The first available [EditorCandidate], or `null` when the list
     * is empty or no candidate is available.
     */
    fun chooseEditor(candidates: List<EditorCandidate>): EditorCandidate? =
        candidates.firstOrNull { it.available }
}
