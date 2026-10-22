package com.termux.shared.termux.proot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Pure classifier for the device GPU family and the action gate of the Adreno
 * KGSL acceleration pipeline (spec 011). All data enters as injected strings so
 * results are deterministic and testable without Android, disk or root (RF-1).
 *
 * <p>Motives (reasons) are stable English keys for later i18n mapping in
 * {@code strings.xml}; no user-visible text is produced here (RF-6).</p>
 */
public final class GpuDetectionLibrary {

    private static final String LOG_TAG = "GpuDetectionLibrary";

    /** Identifiers hinting at an Adreno GPU without KGSL evidence (ADRENO_OTRO). */
    private static final Pattern ADRENO_HINT = Pattern.compile("adreno|qcom|sc[0-9]", Pattern.CASE_INSENSITIVE);
    /** Identifiers hinting at a Mali GPU. */
    private static final Pattern MALI_HINT = Pattern.compile("mali|mediatek|mt[0-9]", Pattern.CASE_INSENSITIVE);
    /** Identifiers hinting at a PowerVR GPU. */
    private static final Pattern POWERVR_HINT = Pattern.compile("powervr|imagination", Pattern.CASE_INSENSITIVE);

    /** Stable i18n key: no GPU identifier is readable at all. */
    public static final String REASON_NO_GPU_DATA = "no_gpu_data";
    /** Stable i18n key: identifiers exist but none matches a known family. */
    public static final String REASON_UNRECOGNIZED_GPU = "unrecognized_gpu";
    /** Stable i18n key: the Debian rootfs is not installed (RF-6). */
    public static final String REASON_ROOTFS_NOT_INSTALLED = "rootfs_not_installed";
    /** Stable i18n key: guest mesa version diverges from the expected one (RF-6). */
    public static final String REASON_MESA_VERSION_MISMATCH = "mesa_version_mismatch";
    /** Stable i18n key: the device family is not ADRENO_CON_KGSL (RF-6). */
    public static final String REASON_FAMILY_UNSUPPORTED = "family_unsupported";

    /** Stable i18n key: panel state line for ADRENO_CON_KGSL (RF-2). */
    public static final String STATE_KEY_ADRENO_KGSL = "gpu_state_adreno_kgsl";
    /** Stable i18n key: panel state line for ADRENO_OTRO (RF-2). */
    public static final String STATE_KEY_ADRENO_OTHER = "gpu_state_adreno_other";
    /** Stable i18n key: panel state line for MALI (RF-2, «…llvmpipe (CPU) seguirá en uso»). */
    public static final String STATE_KEY_MALI = "gpu_state_mali";
    /** Stable i18n key: panel state line for POWERVR (RF-2). */
    public static final String STATE_KEY_POWERVR = "gpu_state_powervr";
    /** Stable i18n key: panel state line for DESCONOCIDA (RF-2). */
    public static final String STATE_KEY_UNKNOWN = "gpu_state_unknown";

    /** Device GPU family classification (RF-1). */
    public enum GpuFamily {
        /** Kernel exposes KGSL (sysfs {@code kgsl-3d0} and/or {@code /dev/kgsl-3d0}). */
        ADRENO_CON_KGSL,
        /** Adreno GPU without KGSL evidence: no acceleration available (RF-6). */
        ADRENO_OTRO,
        /** Mali GPU: no acceleration available (RF-6). */
        MALI,
        /** PowerVR GPU: no acceleration available (RF-6). */
        POWERVR,
        /** No readable data or unrecognized identifier: nothing is guessed (RF-1). */
        DESCONOCIDA
    }

    /**
     * Persisted pipeline preference shared with the app prefs getter/setter of
     * T3 ({@code gpu_solution} key). {@code NONE} is the default; only
     * {@code ADRENO_KGSL} marks a completed acceleration install (RF-3/RF-5).
     */
    public enum Solution { NONE, ADRENO_KGSL }

    /** Action the panel may offer (RF-3, RF-5, RF-6). */
    public enum Action {
        /** Offer "Instalar aceleración GPU" (gate fully passes, no pref active). */
        INSTALL_OFFERED,
        /** Offer "Desinstalar aceleración GPU" (pref active, incl. revert paths). */
        UNINSTALL_OFFERED,
        /** Information only: no installation action is offered (RF-6). */
        INFO_ONLY
    }

    /** Injected device GPU data read by the Android glue ({@link GpuDeviceReader}, T2). */
    public static final class GpuInputs {
        /** {@code /sys/class/kgsl/kgsl-3d0/gpu_model} content, or {@code null} when unreadable. */
        @Nullable public final String gpuModel;
        /** Whether {@code /dev/kgsl-3d0} exists. */
        public final boolean kgslDevNode;
        /** {@code ro.hardware.egl} sysprop, or {@code null}. */
        @Nullable public final String hwEgl;
        /** {@code ro.board.platform} sysprop, or {@code null}. */
        @Nullable public final String boardPlatform;
        /** {@code Build.SOC_MODEL} (already filtered by API level in the glue), or {@code null}. */
        @Nullable public final String socModel;

        /**
         * @param gpuModel Raw sysfs gpu_model text (may carry whitespace/newlines), or {@code null}.
         * @param kgslDevNode Whether the KGSL device node exists.
         * @param hwEgl EGL hardware sysprop value, or {@code null}.
         * @param boardPlatform Board platform sysprop value, or {@code null}.
         * @param socModel SOCs SOC model value, or {@code null}.
         */
        public GpuInputs(@Nullable String gpuModel, boolean kgslDevNode,
                         @Nullable String hwEgl, @Nullable String boardPlatform,
                         @Nullable String socModel) {
            this.gpuModel = gpuModel;
            this.kgslDevNode = kgslDevNode;
            this.hwEgl = hwEgl;
            this.boardPlatform = boardPlatform;
            this.socModel = socModel;
        }
    }

    /** Result of {@link #detectFamily}: family plus optional sanitized model and reason. */
    public static final class GpuFamilyResult {
        /** Classified family; never {@code null}. */
        @NonNull public final GpuFamily family;
        /** Sanitized sysfs model (trimmed, no line breaks) for ADRENO_CON_KGSL, else {@code null}. */
        @Nullable public final String model;
        /** Stable i18n reason key when {@code DESCONOCIDA}, else {@code null}. */
        @Nullable public final String reason;

        /**
         * @param family Classified GPU family.
         * @param model Sanitized GPU model, or {@code null}.
         * @param reason Stable reason key for {@code DESCONOCIDA}, or {@code null}.
         */
        GpuFamilyResult(@NonNull GpuFamily family, @Nullable String model, @Nullable String reason) {
            this.family = family;
            this.model = model;
            this.reason = reason;
        }
    }

    /** Action gate decision: what the panel may offer and why not otherwise (RF-3, RF-5, RF-6). */
    public static final class ActionGate {
        /** Offered action; never {@code null}. */
        @NonNull public final Action action;
        /** Stable i18n reason key for INFO_ONLY and for the revert variant of UNINSTALL_OFFERED; else {@code null}. */
        @Nullable public final String reason;

        /**
         * @param action Offered action.
         * @param reason Stable reason key, or {@code null} when fully satisfied.
         */
        ActionGate(@NonNull Action action, @Nullable String reason) {
            this.action = action;
            this.reason = reason;
        }
    }

    private GpuDetectionLibrary() {
        // Static utility class (per project architecture rules).
    }

    /**
     * Stable i18n keys for the settings panel (T8, RF-2, RF-6): the per-family
     * honest state line, the only-info motive when one applies and the action
     * the panel may offer. Purely carried keys — the app maps them to
     * {@code strings.xml} resources; no user-visible text is produced here.
     */
    public static final class GpuUiKeys {

        /** Panel state-line i18n key; matches a {@link GpuFamily}; never {@code null}. */
        @NonNull public final String stateKey;
        /** Motive i18n key when applicable ({@link #toUiKeys} decides), else {@code null}. */
        @Nullable public final String reasonKey;
        /** Offered action from the {@link ActionGate}; never {@code null}. */
        @NonNull public final Action action;

        /**
         * @param stateKey Stable state-line i18n key.
         * @param reasonKey Stable motive i18n key, or {@code null}.
         * @param action Offered action.
         */
        GpuUiKeys(@NonNull String stateKey, @Nullable String reasonKey, @NonNull Action action) {
            this.stateKey = stateKey;
            this.reasonKey = reasonKey;
            this.action = action;
        }
    }

    /**
     * Map a detection result and its action gate onto the stable panel i18n
     * keys (T8, RF-2, RF-6): the state line follows the family; the motive
     * prefers the family's own detection motive — for {@code DESCONOCIDA} it
     * explains WHY the family is unknown (RF-2, «con motivo») over the gate's
     * blocking motive — and otherwise the gate's only-info motive; the action
     * is the gate's own decision (the gate rules over the panel, RF-3/RF-5).
     *
     * <p>Null inputs degrade safely: an absent {@link GpuFamilyResult} reads
     * as DESCONOCIDA/{@link #REASON_NO_GPU_DATA} and an absent
     * {@link ActionGate} resolves to only-information, so this function never
     * throws nor produces a guessed family.</p>
     *
     * @param familyResult The result of {@link #detectFamily}, may be {@code null}.
     * @param gate The result of {@link #evaluateAction}, may be {@code null}.
     * @return Returns the stable keys for the panel; never {@code null}.
     */
    @NonNull
    public static GpuUiKeys toUiKeys(@Nullable GpuFamilyResult familyResult,
                                     @Nullable ActionGate gate) {
        GpuFamily family = familyResult != null ? familyResult.family : GpuFamily.DESCONOCIDA;
        Action action = gate != null ? gate.action : Action.INFO_ONLY;
        String familyReason = familyResult != null ? familyResult.reason : null;
        String gateReason = gate != null ? gate.reason : null;

        String reasonKey;
        if (family == GpuFamily.DESCONOCIDA && familyReason != null)
            reasonKey = familyReason;
        else if (gateReason != null)
            reasonKey = gateReason;
        else if (familyReason != null)
            reasonKey = familyReason;
        else if (family == GpuFamily.DESCONOCIDA)
            // Only reachable with no detectFamily result at all: no reason is
            // composed when nothing was read (JSON-safe degrade, not a guess).
            reasonKey = REASON_NO_GPU_DATA;
        else
            reasonKey = null;

        return new GpuUiKeys(stateKeyFor(family), reasonKey, action);
    }

    /**
     * The stable state-line i18n key of a family (RF-2): every family shows its
     * honest text, including the families with no acceleration available
     * (Mali: «…llvmpipe (CPU) seguirá en uso»).
     *
     * @param family The family to key, may be {@code null}.
     * @return Returns the key; {@code null} degrades to the DESCONOCIDA key.
     */
    @NonNull
    public static String stateKeyFor(@Nullable GpuFamily family) {
        if (family == null) return STATE_KEY_UNKNOWN;
        switch (family) {
            case ADRENO_CON_KGSL: return STATE_KEY_ADRENO_KGSL;
            case ADRENO_OTRO: return STATE_KEY_ADRENO_OTHER;
            case MALI: return STATE_KEY_MALI;
            case POWERVR: return STATE_KEY_POWERVR;
            default: return STATE_KEY_UNKNOWN;
        }
    }

    /**
     * Classify the device GPU family from injected inputs, in the priority order
     * of RF-1: (a) sysfs {@code gpu_model} or the KGSL device node is the kernel
     * truth and yields ADRENO_CON_KGSL; (b-c-d) without KGSL, the sysprop and
     * SOC_MODEL identifiers are matched against known family keywords. Any
     * absence of data never results in a guessed family: it falls back to
     * DESCONOCIDA with the motive recorded.
     *
     * @param in Injected device GPU data (may contain {@code null}s; nothing dominates).
     * @return Returns the family with the sanitized model and reason filled when applicable.
     */
    @NonNull
    public static GpuFamilyResult detectFamily(@Nullable GpuInputs in) {
        if (in == null)
            return new GpuFamilyResult(GpuFamily.DESCONOCIDA, null, REASON_NO_GPU_DATA);

        String model = null;
        if (in.gpuModel != null) {
            String trimmed = collapseWhitespace(in.gpuModel);
            if (!trimmed.isEmpty()) {
                model = trimmed;
            }
        }
        // (a) Kernel truth: sysfs kgsl model or the /dev/kgsl-3d0 node.
        if (model != null || in.kgslDevNode)
            return new GpuFamilyResult(GpuFamily.ADRENO_CON_KGSL, model, null);

        // (b-c-d) Without KGSL nothing is "guessed" as ADRENO_CON_KGSL; the
        // sysfs gpu_model already implied KGSL, so it is not re-examined here.
        boolean anyIdentifier = false;
        String hwEgl = in.hwEgl == null ? "" : in.hwEgl.trim();
        String board = in.boardPlatform == null ? "" : in.boardPlatform.trim();
        String soc = in.socModel == null ? "" : in.socModel.trim();
        for (String id : new String[]{hwEgl, board, soc}) {
            if (id.isEmpty()) continue;
            anyIdentifier = true;
            if (ADRENO_HINT.matcher(id).find())
                return new GpuFamilyResult(GpuFamily.ADRENO_OTRO, null, null);
            if (MALI_HINT.matcher(id).find())
                return new GpuFamilyResult(GpuFamily.MALI, null, null);
            if (POWERVR_HINT.matcher(id).find())
                return new GpuFamilyResult(GpuFamily.POWERVR, null, null);
        }
        return new GpuFamilyResult(GpuFamily.DESCONOCIDA, null,
            anyIdentifier ? REASON_UNRECOGNIZED_GPU : REASON_NO_GPU_DATA);
    }

    /**
     * Evaluate the action gate of the acceleration pipeline (RF-3, RF-5, RF-6).
     * Evaluation order: rootfs installed → guest mesa version → family; the
     * first failing condition decides the INFO_ONLY motive. When the
     * {@code ADRENO_KGSL} solution is active, a failed rootfs or version check
     * still offers the uninstall (with the motive noted) so the user can
     * always revert; the gate rules over the preference, never the opposite.
     *
     * @param family Family from {@link #detectFamily}.
     * @param rootfsInstalled Whether the Debian rootfs is installed.
     * @param guestMesaVersion Mesa version of the guest packages, or {@code null} when unknown.
     * @param expectedMesaVersion Expected pinned mesa version.
     * @param currentSolution Current persisted pipeline solution.
     * @return Returns the gate decision with the reason key when applicable.
     */
    @NonNull
    public static ActionGate evaluateAction(@Nullable GpuFamily family, boolean rootfsInstalled,
                                            @Nullable String guestMesaVersion,
                                            @NonNull String expectedMesaVersion,
                                            @Nullable Solution currentSolution) {
        boolean rootfsOk = rootfsInstalled;
        boolean versionOk = guestMesaVersion != null && guestMesaVersion.equals(expectedMesaVersion);
        boolean familyOk = family == GpuFamily.ADRENO_CON_KGSL;
        boolean solutionActive = currentSolution == Solution.ADRENO_KGSL;

        // Reversibility: with the solution active a failed rootfs/version
        // check routes to uninstall (noting why) instead of blocking it.
        if (solutionActive) {
            if (!rootfsOk) return new ActionGate(Action.UNINSTALL_OFFERED, REASON_ROOTFS_NOT_INSTALLED);
            if (!versionOk) return new ActionGate(Action.UNINSTALL_OFFERED, REASON_MESA_VERSION_MISMATCH);
            if (!familyOk) return new ActionGate(Action.INFO_ONLY, REASON_FAMILY_UNSUPPORTED);
            return new ActionGate(Action.UNINSTALL_OFFERED, null);
        }

        if (!rootfsOk) return new ActionGate(Action.INFO_ONLY, REASON_ROOTFS_NOT_INSTALLED);
        if (!versionOk) return new ActionGate(Action.INFO_ONLY, REASON_MESA_VERSION_MISMATCH);
        if (!familyOk) return new ActionGate(Action.INFO_ONLY, REASON_FAMILY_UNSUPPORTED);
        return new ActionGate(Action.INSTALL_OFFERED, null);
    }

    /**
     * Sanitize a raw sysfs field: collapse every whitespace run (including
     * line breaks) into a single space and trim the ends.
     *
     * @param raw Raw field text, possibly {@code null}.
     * @return Returns the sanitized text; empty string when nothing usable.
     */
    @NonNull
    static String collapseWhitespace(@Nullable String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", " ").trim();
    }
}
