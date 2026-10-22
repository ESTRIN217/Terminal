package com.termux.shared.termux.proot;

import java.util.LinkedHashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Pure builders for the apt commands the guest runs to install/remove the pinned Mesa
 * acceleration, and a tolerant parser for the guest {@code dpkg-query} output
 * (spec 011, RF-3, RF-5, RF-6). No Android API, no I/O, no time: the actual execution
 * is decided downstream ({@code GpuAccelerationInstaller}, T5/T6).
 *
 * <p>The {@code .deb} download URLs are built by {@link GpuDebCatalog#debUrlFor(String, String)};
 * download and SHA-256 verification happen before these commands are ever executed.</p>
 *
 * <p>This class must not use any Android API, so it stays unit-testable with plain JUnit.</p>
 */
public final class GpuGuestCommands {

    private static final String LOG_TAG = "GpuGuestCommands";

    /** Prefix that makes apt non-interactive; ends with ";" so the steps chain with "&&". */
    private static final String NONINTERACTIVE_PREFIX = "export DEBIAN_FRONTEND=noninteractive; ";

    /**
     * Exit marker the one-shot guest command echoes at the end
     * ({@code echo GPU_APT_EXIT:$?}): the proot session pid's own exit status
     * can be 0 even when the guest chain failed (the trailing echo runs), so the
     * {@linkplain #parseExitMarker(String) parsed marker} is the authoritative
     * exit code (spec 011, plan «Interfaz del runner de comandos en el invitado»).
     */
    public static final String EXIT_MARKER_PREFIX = "GPU_APT_EXIT:";

    private GpuGuestCommands() {
    }

    /**
     * Build the guest install command: plain {@code apt-get update}, then reinstall from the
     * local {@code .deb} files ({@code ./} prefix is mandatory for apt), then hold all the
     * packages. The three steps are chained with {@code &&} so an apt lock, a failed update
     * or a failed install aborts the whole chain (nothing partial happens, RF-3).
     *
     * <p>Empty arrays degrade the command to fewer chained segments (no exception is thrown,
     * following the repo rule of error objects over throws); callers are expected to always
     * pass the six real file names and the six real packages.</p>
     *
     * @param debFileNames The .deb file names present in the guest working directory,
     *                     in the documented install order; they are emitted with the
     *                     {@code ./} prefix required by apt.
     * @param packages     The package names matching {@code debFileNames}, in the same order;
     *                     emitted without the {@code ./} prefix (apt-mark takes packages,
     *                     not files).
     * @return The full command line to execute inside the guest, e.g.
     *         {@code export DEBIAN_FRONTEND=noninteractive; apt-get update &&
     *         apt install --reinstall -y ./deb1 ... && apt-mark hold pkg1 ...}.
     */
    @NonNull
    public static String buildInstallCommand(@NonNull String[] debFileNames,
                                             @NonNull String[] packages) {
        StringBuilder cmd = new StringBuilder(NONINTERACTIVE_PREFIX);
        cmd.append("apt-get update");

        if (debFileNames.length > 0) {
            cmd.append(" && apt install --reinstall -y ");
            cmd.append(join(debFileNames, "./"));
        }

        if (packages.length > 0) {
            cmd.append(" && apt-mark hold ");
            cmd.append(join(packages, ""));
        }

        return cmd.toString();
    }

    /**
     * Build the guest uninstall command (mirror Debian, RF-5): unhold all packages, then
     * reinstall them from the Debian mirror — no local {@code .deb} files, so the guest
     * goes back byte-for-byte to the distro state (NFR-5). The steps are chained with
     * {@code &&} so an apt lock or a failed unhold aborts before the reinstall.
     *
     * <p>Empty arrays degrade the command to {@code ...; apt-get update} (no exception,
     * see {@link #buildInstallCommand(String[], String[])}).</p>
     *
     * @param packages The six Mesa package names, in the documented install order.
     * @return The full command line to execute inside the guest, e.g.
     *         {@code export DEBIAN_FRONTEND=noninteractive; apt-get update &&
     *         apt-mark unhold pkg1 ... && apt install --reinstall -y pkg1 ...}.
     */
    @NonNull
    public static String buildUninstallCommand(@NonNull String[] packages) {
        StringBuilder cmd = new StringBuilder(NONINTERACTIVE_PREFIX);
        cmd.append("apt-get update");

        if (packages.length > 0) {
            cmd.append(" && apt-mark unhold ").append(join(packages, ""));
            cmd.append(" && apt install --reinstall -y ").append(join(packages, ""));
        }

        return cmd.toString();
    }

    /**
     * Parse the output of
     * {@code dpkg-query -W -f='${Package}\t${Version}\n' <packages>} into a package → version
     * map (RF-3, RF-6). Every well-formed {@code pkg\tversion} line becomes an entry; every
     * other line is ignored, including dpkg-query complaints like
     * {@code dpkg-query: no packages found matching <pkg>} — their package simply reads as
     * absent via {@code map.get(pkg) == null} (the {@link GpuDetectionLibrary} gate
     * interprets a missing version as "diverges").
     *
     * <p>The caller decides how to run dpkg-query: with {@code -W} dpkg-query exits non-zero
     * when a queried package is absent, so the caller (T5/T6) will typically run it with
     * {@code set +e} and feed the whole output here; a null, empty or ragged input never
     * crashes — it just yields a smaller or empty map.</p>
     *
     * @param dpkgQueryOutput The raw captured output of the guest dpkg-query rule; may be
     *                        {@code null}.
     * @return A package → version map (insertion order preserved); empty when nothing in the
     *         input is a valid {@code pkg\tversion} line. Entries whose version is empty
     *         are treated as absent, not as empty strings.
     */
    @NonNull
    public static Map<String, String> parseDpkgVersions(@Nullable String dpkgQueryOutput) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (dpkgQueryOutput == null || dpkgQueryOutput.isEmpty())
            return versions;

        String[] lines = dpkgQueryOutput.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            // dpkg-query complaint lines never take a version value, even if a future
            // format embeds the package name in them.
            if (trimmed.isEmpty() || trimmed.startsWith("dpkg-query:"))
                continue;

            // Take at most three fields; anything beyond the second field is noise and is
            // ignored, so the version never carries a trailing tab fragment.
            String[] fields = trimmed.split("\t", 3);
            if (fields.length < 2)
                continue;

            String pkg = fields[0].trim();
            String version = fields[1].trim();
            if (pkg.isEmpty() || version.isEmpty())
                continue;

            versions.put(pkg, version);
        }
        return versions;
    }

    /**
     * Build the read-only guest rule that reveals the exact version of the six
     * pinned Mesa packages (RF-6): the output feeds
     * {@link #parseDpkgVersions(String)} so the panel's action gate can compare
     * the guest versions against {@code DEBIAN_MESA_EXPECTED_VERSION} (T8).
     *
     * <p>With {@code -W}, dpkg-query exits non-zero when a queried package is
     * absent; bash inherits no {@code set -e}, so nothing aborts and the
     * missing package simply reads absent through the parser.</p>
     *
     * @param packages The package names to query, in the documented install
     *                 order; may be {@code null} or empty (the rule then
     *                 queries nothing and returns no versions).
     * @return The command line to execute inside the guest, e.g.
     *         {@code dpkg-query -W -f='${Package}\t${Version}\n' libegl-mesa0 ...}.
     */
    @NonNull
    public static String buildDpkgQueryCommand(@Nullable String[] packages) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("dpkg-query -W -f='${Package}\\t${Version}\\n'");
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty())
                    continue;
                cmd.append(' ').append(pkg);
            }
        }
        return cmd.toString();
    }

    /**
     * Append the one-shot exit marker to a guest command so the caller (T6
     * {@code GpuAccelerationInstaller}) can read the chain's real exit code from
     * the session transcript: {@code <command>; echo GPU_APT_EXIT:$?}.
     *
     * <p>The {@code echo} is separated with {@code ";"}, NOT {@code "&&"}, so the
     * marker is emitted even when the chained apt steps fail; a missing second
     * command would leave the real exit code inside the shell only.</p>
     *
     * @param guestCommand The plain guest command, e.g. the output of
     *                     {@link #buildInstallCommand(String[], String[])}.
     * @return The command to run with {@code bash -c} inside the guest, marker
     *         appended.
     */
    @NonNull
    public static String assembleOneShotGuestCommand(@NonNull String guestCommand) {
        return guestCommand + "; echo " + EXIT_MARKER_PREFIX + "$?";
    }

    /**
     * Parse the LAST {@value #EXIT_MARKER_PREFIX}<code>&lt;digits&gt;</code> marker out
     * of a one-shot guest session transcript (plan decision: pure parse of the
     * exit marker persisted in the session output).
     *
     * <p>Only decimal digits are read, and the digit run stops at the first
     * non-digit, so trailing prose ({@code GPU_APT_EXIT:0 (done)}) is ignored.
     * When several markers appear (stray command re-echoing the marker), the
     * last valid one wins. A marker without a parseable decimal value does not
     * shadow an earlier valid one.</p>
     *
     * @param output The session transcript (may be {@code null}).
     * @return The marker's exit code, or {@code -1} when no valid marker exists.
     */
    public static int parseExitMarker(@Nullable String output) {
        int result = -1;
        if (output == null || output.isEmpty())
            return result;

        int from = 0;
        while (true) {
            int marker = output.indexOf(EXIT_MARKER_PREFIX, from);
            if (marker == -1)
                break;

            int valueStart = marker + EXIT_MARKER_PREFIX.length();
            int valueEnd = valueStart;
            while (valueEnd < output.length() && output.charAt(valueEnd) >= '0'
                && output.charAt(valueEnd) <= '9')
                valueEnd++;

            if (valueEnd > valueStart) {
                // Absurdly many digits cannot be an exit code: a marker that
                // fails to parse must not override an earlier valid one.
                try {
                    result = Integer.parseInt(output.substring(valueStart, valueEnd));
                } catch (NumberFormatException ignored) {
                    // Keep scanning for (or remember) an earlier valid marker.
                }
                from = valueEnd;
            } else {
                from = valueStart;
            }
        }
        return result;
    }

    /**
     * Pure preference transition of the {@code gpu_solution} pref after a
     * one-shot guest apt run (RF-3, RF-5): exit 0 promotes the target solution
     * ({@code ADRENO_KGSL} for install, {@code NONE} for uninstall), any other
     * exit — including {@code -1}, the «unverifiable» sentinel — keeps the
     * current solution untouched ({@code null} = keep).
     *
     * <p>This is the «extracto purificado» the T6 transition tests cover; the
     * glue only wires its result into {@code TermuxAppSharedPreferences}.</p>
     *
     * @param currentSolution The value the pref holds right now (informational
     *                        only; may be {@code null}).
     * @param exitCode        The resolved guest exit code ({@code 0} = success,
     *                        {@code != 0} = failure, {@code -1} = unverifiable).
     * @param uninstall       {@code true} for the uninstall action, {@code false}
     *                        for the install action.
     * @return The new solution to persist, or {@code null} to keep the current
     *         one (failure).
     */
    @Nullable
    public static GpuDetectionLibrary.Solution computeNewSolution(
        @Nullable GpuDetectionLibrary.Solution currentSolution, int exitCode, boolean uninstall) {
        if (exitCode != 0)
            return null;
        return uninstall ? GpuDetectionLibrary.Solution.NONE
            : GpuDetectionLibrary.Solution.ADRENO_KGSL;
    }

    /**
     * Common mesa version reported by ALL six catalog packages (review fix for
     * RF-3: the first present version is not decisive — a partially diverging
     * guest must not pass the gate).
     *
     * <p>Every package in {@link GpuDebCatalog#PACKAGE_NAMES} must be present in
     * {@code versions} with the exact same non-empty version string; otherwise
     * the guest counts as diverging for the pure gate (RF-6) and this returns
     * {@code null}. Extra entries in the map are ignored.</p>
     *
     * @param versions Map produced by {@link #parseDpkgVersions} (may be {@code null}).
     * @return The common version string, or {@code null} when absent/diverging.
     */
    @Nullable
    public static String findCommonMesaVersion(@Nullable Map<String, String> versions) {
        if (versions == null)
            return null;
        String common = null;
        for (String pkg : GpuDebCatalog.PACKAGE_NAMES) {
            String version = versions.get(pkg);
            if (version == null || version.trim().isEmpty())
                return null;
            if (common == null) {
                common = version;
            } else if (!common.equals(version)) {
                return null;
            }
        }
        return common;
    }

    /**
     * Join space-separated items, where every item carries a per-item prefix
     * (e.g. {@code "./"} for apt file arguments).
     *
     * @param items  The items, all non-empty by the caller's contract.
     * @param prefix The per-item prefix ({@code "./"} or empty string).
     * @return {@code prefix + items[0] + " " + prefix + items[1] ...}.
     */
    @NonNull
    private static String join(@NonNull String[] items, @NonNull String prefix) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0)
                joined.append(' ');
            joined.append(prefix).append(items[i]);
        }
        return joined.toString();
    }

}
