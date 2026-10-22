package com.termux.shared.termux.proot;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure library for the llvmpipe multithreading lever of spec 012 (RF-1):
 * it computes the thread count from the injected raw core count and the user
 * preference, and composes the guest environment entries. All data enters as
 * parameters so results are deterministic and testable without Android, disk
 * or root; no user-visible text is produced here (i18n lives in the app).
 *
 * <p>The whole environment injection is skipped when the GPU acceleration of
 * spec 011 is active ({@link #shouldInject} is the gate; the flag arrives
 * injected as a boolean).</p>
 */
public final class LlvmpipeThreadsLibrary {

    private static final String LOG_TAG = "LlvmpipeThreadsLibrary";

    /** Guest env variable name honored by the classic llvmpipe path. */
    public static final String ENV_LP_NUM_THREADS = "LP_NUM_THREADS";
    /** Guest env variable name honored by the modern gallium path. */
    public static final String ENV_GALLIUM_NUM_THREADS = "GALLIUM_NUM_THREADS";

    /** Ceiling for the raw core count: ROMs that lie about cores are clamped to it. */
    public static final int MAX_CORES = 32;
    /** Ceiling for the user preference N (matches the UI slider upper bound). */
    public static final int MAX_THREADS = 16;
    /** Default user preference N when nothing was configured. */
    public static final int DEFAULT_THREADS = 8;

    private LlvmpipeThreadsLibrary() {
        // Static utility class (per project architecture rules).
    }

    /**
     * Compute the llvmpipe thread count (RF-1): both inputs are clamped
     * <em>before</em> the {@code min}, so out-of-range values never leak into
     * the result. The clamps are {@code cores = clamp(coresRaw, 1, 32)} (ROMs
     * lying high, or reporting nothing) and {@code N = clamp(prefN, 1, 16)};
     * the result is {@code min(cores, N)}.
     *
     * @param coresRaw The raw core count as reported by the host ({@code
     *                 availableProcessors()} in the glue), possibly out of range.
     * @param prefN The user preference N (1..16 in the UI), possibly out of range.
     * @return Returns {@code min(clamp(coresRaw, 1, 32), clamp(prefN, 1, 16))}; always ≥ 1.
     */
    public static int computeThreads(int coresRaw, int prefN) {
        int cores = clamp(coresRaw, 1, MAX_CORES);
        int n = clamp(prefN, 1, MAX_THREADS);
        return Math.min(cores, n);
    }

    /**
     * Compose the guest environment entries for the computed thread count
     * (RF-1): both {@link #ENV_LP_NUM_THREADS} and {@link #ENV_GALLIUM_NUM_THREADS}
     * carry the same value string, since which one a mesa build honors depends
     * on the binary. A thread count below 1 is absurd and injects nothing.
     *
     * @param threads Thread count from {@link #computeThreads}.
     * @return Returns an unmodifiable map with the two entries, or an empty map
     *         when {@code threads < 1}.
     */
    @NonNull
    public static Map<String, String> llvmpipeEnvEntries(int threads) {
        if (threads < 1)
            return Collections.emptyMap();

        Map<String, String> entries = new HashMap<>();
        entries.put(ENV_LP_NUM_THREADS, String.valueOf(threads));
        entries.put(ENV_GALLIUM_NUM_THREADS, String.valueOf(threads));
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Decide whether the llvmpipe entries should be injected at all (RF-2, RF-4):
     * only with the lever enabled and the GPU <em>not</em> accelerated. With the
     * GPU accelerated (Adreno with KGSL active) the whole calculation is not
     * read (short-circuit); the flag arrives injected from the spec 011 glue.
     *
     * @param enabled Whether the llvmpipe threads preference is enabled.
     * @param gpuAccelerated Whether the GPU acceleration of spec 011 is active.
     * @return Returns {@code enabled && !gpuAccelerated}.
     */
    public static boolean shouldInject(boolean enabled, boolean gpuAccelerated) {
        return enabled && !gpuAccelerated;
    }

    /**
     * Clamp a value into {@code [min, max]}.
     *
     * @param value The value to clamp.
     * @param min Lower bound (inclusive).
     * @param max Upper bound (inclusive).
     * @return Returns the value clamped into the range.
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
