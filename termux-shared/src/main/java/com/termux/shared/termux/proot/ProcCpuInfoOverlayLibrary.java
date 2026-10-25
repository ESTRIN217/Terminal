package com.termux.shared.termux.proot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure builders for the synthetic {@code /proc/cpuinfo} snapshot served to
 * proot guests (spec 019).
 *
 * <p>Android's arm64 kernels do not emit a {@code model name} line in
 * {@code /proc/cpuinfo}. Tools ported from desktop Linux (btop's
 * {@code Cpu::get_cpuName()}) scan for that line and, when it is missing, fall
 * back to enumerating {@code /sys/devices} — a {@code readdir} the
 * {@code untrusted_app} uid cannot perform on restrictive OEM SELinux policies
 * ({@code docs/proot-debian-arm64-plan.md §7}). Serving a verbatim copy of the
 * host cpuinfo enriched with one {@code model name} line keeps those tools on
 * the native path (same bind pattern as spec 010).</p>
 *
 * <p>Like {@link ProcStatOverlayLibrary}, everything here is pure: no Android
 * identifier is read, so the tests run on plain JVM.</p>
 */
public final class ProcCpuInfoOverlayLibrary {

    private ProcCpuInfoOverlayLibrary() {
        // Static utility class (per project architecture rules).
    }

    /**
     * Whether the cpuinfo text lacks a {@code model name} line in the exact way
     * btop's scan misses it.
     *
     * <p>The replication of btop's loop ({@code getline(..., ':')} followed by
     * {@code ignore(..., '\n')}) is: for every chunk, take the text from the
     * current position up to the next {@code ':'} and test it against
     * {@code startsWith("model name")}; after a colon, skip to the end of that
     * line before starting the next chunk (a line without any colon simply
     * continues into the next one). Leading whitespace or other text before the
     * check therefore loses the match, bug-for-bug.</p>
     *
     * @param cpuInfoText Host {@code /proc/cpuinfo} text, or {@code null}.
     * @return Returns {@code true} when btop's scan would end at EOF without a
     * match and fall back to listing {@code /sys/devices}.
     */
    public static boolean needsModelNameLine(@Nullable String cpuInfoText) {
        if (cpuInfoText == null) return true;
        int pos = 0;
        int len = cpuInfoText.length();
        while (pos < len) {
            int colon = cpuInfoText.indexOf(':', pos);
            String chunk;
            if (colon < 0) {
                chunk = cpuInfoText.substring(pos);
                pos = len;
            } else {
                chunk = cpuInfoText.substring(pos, colon);
                int lineEnd = cpuInfoText.indexOf('\n', colon);
                pos = (lineEnd < 0) ? len : lineEnd + 1;
            }
            if (chunk.startsWith("model name")) return false;
        }
        return true;
    }

    /**
     * Choose the presentable CPU name from the Android device labels.
     *
     * <p>{@code socModel} ({@code Build.SOC_MODEL}, API 31+ per its caller's
     * guard) wins; {@code hardware} ({@code Build.HARDWARE}) is the fallback.
     * Blank values and the AOSP placeholder {@code unknown} are unusable, and
     * names longer than {@value #MAX_NAME_LENGTH} characters are rejected
     * outright (a truncated "model name" presented to users is worse than no
     * overlay).</p>
     *
     * @param socModel Preferred SoC model label, or {@code null}.
     * @param hardware Fallback hardware label, or {@code null}.
     * @return Returns the sanitized name, or {@code null} when neither label is
     * usable (caller keeps the native path, spec 019 RF-3).
     */
    @Nullable
    public static String presentableCpuName(@Nullable String socModel, @Nullable String hardware) {
        String soc = sanitizedUsableName(socModel);
        if (soc != null) return soc;
        return sanitizedUsableName(hardware);
    }

    /**
     * Build the synthetic cpuinfo text: verbatim host content plus one final
     * {@code model name} line (spec 019 RF-5).
     *
     * @param hostCpuInfoText The read host cpuinfo text; appended line is
     *                        separated with exactly one newline.
     * @param modelName       Presentable model name (from
     *                        {@link #presentableCpuName(String, String)}),
     *                        sanitized defensively here (no {@code ':'} or line
     *                        breaks); {@code null} returns the text unchanged.
     * @return Returns the snapshot text, or the input unchanged when nothing can
     * be appended (native pure path).
     */
    @NonNull
    public static String buildSyntheticCpuInfo(@Nullable String hostCpuInfoText,
                                               @Nullable String modelName) {
        if (hostCpuInfoText == null) return "";
        String name = sanitize(modelName);
        if (name.isEmpty()) return hostCpuInfoText;
        String body = hostCpuInfoText;
        while (body.endsWith("\n") || body.endsWith("\r"))
            body = body.substring(0, body.length() - 1);
        return body + "\nmodel name : " + name + "\n";
    }

    /**
     * Session-glue decision (spec 019 RF-2/RF-3/RF-4): the cpuinfo overlay
     * applies only when the host text was readable, btop-style scan would miss
     * the model name, and a usable name exists.
     *
     * @param cpuInfoText Read host cpuinfo text, or {@code null} when the read
     *                    failed (RF-4 keeps the native path).
     * @param presentableName Result of {@link #presentableCpuName(String, String)},
     *                        or {@code null}.
     * @return Returns {@code true} when the snapshot must be published and bound.
     */
    public static boolean cpuInfoOverlayNeeded(@Nullable String cpuInfoText,
                                               @Nullable String presentableName) {
        return presentableName != null && cpuInfoText != null
            && needsModelNameLine(cpuInfoText);
    }

    /**
     * Sanitize a candidate name or report it unusable: collapse every
     * whitespace run (including tabs and line breaks) to single spaces, trim,
     * reject the AOSP {@code unknown} placeholder and enforce the length cap.
     */
    @Nullable
    private static String sanitizedUsableName(@Nullable String value) {
        String name = sanitize(value);
        if (name.isEmpty()) return null;
        if ("unknown".equalsIgnoreCase(name)) return null;
        if (name.length() > MAX_NAME_LENGTH) return null;
        return name;
    }

    /**
     * Turn any device label into a single-line cpuinfo value: line breaks
     * become spaces, {@code ':'} is removed (keeps the scan semantics stable),
     * whitespace runs collapse, outer whitespace is trimmed.
     */
    @NonNull
    private static String sanitize(@Nullable String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").replace(":", "")
            .replaceAll("\\s+", " ").trim();
    }

    // ---- plan constants ----

    /** Maximum accepted length of the presented model name. */
    static final int MAX_NAME_LENGTH = 64;
}
