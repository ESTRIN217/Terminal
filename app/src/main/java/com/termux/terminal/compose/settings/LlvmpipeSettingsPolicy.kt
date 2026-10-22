package com.termux.terminal.compose.settings

import com.termux.shared.termux.proot.LlvmpipeThreadsLibrary

/**
 * Pure policy and helpers for the llvmpipe multithreading settings UI (spec 012, RF-1, RF-2).
 */
object LlvmpipeSettingsPolicy {

    /**
     * Compute the effective thread count that will be injected into guest processes,
     * respecting host cores and user preference N.
     */
    @JvmStatic
    fun computeEffectiveThreads(coresRaw: Int, prefN: Int): Int {
        return LlvmpipeThreadsLibrary.computeThreads(coresRaw, prefN)
    }

    /**
     * Whether the llvmpipe multithread controls are active and applicable.
     * When GPU acceleration is active (spec 011), llvmpipe controls do not apply.
     */
    @JvmStatic
    fun isApplicable(isGpuSolutionActive: Boolean): Boolean {
        return !isGpuSolutionActive
    }
}
