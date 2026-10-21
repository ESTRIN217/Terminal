package com.estrin217.filemanager.compose

/**
 * Decision on the clipboard after a paste attempt (spec 009, RF-4), derived
 * from pure counts so the ViewModel clears the clipboard and reports the
 * result without inspecting files.
 *
 * @param clearClipboard Whether the cut clipboard should be cleared.
 * @param allFailed Whether nothing moved at all (the UI reports failure
 * instead of "N pasted" with N=0), matching the RF-4 fix of the previous
 * always-success behavior.
 */
data class PasteOutcome(
    val clearClipboard: Boolean,
    val allFailed: Boolean
)

/**
 * Pure policy for paste results (spec 009, RF-4).
 *
 * In cut mode, sources that moved no longer exist at their origin, so the
 * clipboard is cleared as soon as something moved — a partial clear avoids
 * re-pasting already-moved entries on a retry; a full failure instead keeps
 * the clipboard so the paste can be retried. Copy mode never clears the
 * clipboard.
 */
object PastePolicy {

    /**
     * Derives the outcome from the paste counts.
     *
     * @param movedCount How many sources completed the move/copy.
     * @param total How many sources the clipboard held (0 is tolerated and
     * yields neither clearing nor failure even in cut mode).
     * @param isCut Whether the clipboard operation is CUT.
     * @return The [PasteOutcome] the caller must honor.
     */
    fun outcome(movedCount: Int, total: Int, isCut: Boolean): PasteOutcome {
        val allFailed = total > 0 && movedCount == 0
        return PasteOutcome(clearClipboard = isCut && movedCount > 0, allFailed = allFailed)
    }
}
