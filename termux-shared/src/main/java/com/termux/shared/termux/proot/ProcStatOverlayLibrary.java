package com.termux.shared.termux.proot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure builder for the procfs stat snapshots served to guest processes when
 * SELinux hides the kernel's {@code /proc/stat}, {@code /proc/meminfo} and
 * {@code /proc/loadavg} from an {@code untrusted_app} uid (spec 010). All time
 * dependent values enter as explicit parameters so results are deterministic.
 *
 * <p>The synthetic {@code /proc/stat} reports the terminal's own load (the only
 * data Android exposes without root) instead of a fixed install-time sample like
 * the Termux htop package.</p>
 */
public final class ProcStatOverlayLibrary {

    private static final String LOG_TAG = "ProcStatOverlayLibrary";

    /** Advance result of {@link #advanceCpu} between two sampling ticks. */
    public static final class CpuDelta {

        /** Busy jiffies across the interval (user+system, sum of per-pid deltas). */
        public final long busy;
        /** Idle jiffies, clamped to {@code >= 0} when busy exceeds the elapsed budget. */
        public final long idle;
        /** Current samples to be used as the {@code prev} argument of the next tick. */
        @NonNull
        public final Map<Integer, long[]> next;

        /**
         * @param busy Busy jiffies of the interval.
         * @param idle Idle jiffies of the interval (clamped).
         * @param next Samples to carry over to the next tick.
         */
        CpuDelta(long busy, long idle, @NonNull Map<Integer, long[]> next) {
            this.busy = busy;
            this.idle = idle;
            this.next = next;
        }
    }

    /** Values in each cpu line of a standard {@code /proc/stat}: user, nice, system, idle, iowait, irq, softirq, steal, guest, guest_nice. */
    static final int CPU_LINE_VALUE_COUNT = 10;
    /** Trailing zero fields appended after the busy/idle pair (iowait, irq, softirq, steal, guest, guest_nice). */
    static final int CPU_LINE_TRAILING_ZEROS = CPU_LINE_VALUE_COUNT - 4;

    private ProcStatOverlayLibrary() {
        // Static utility class (per project architecture rules).
    }

    /**
     * Advance the own-uid CPU bookkeeping from one sampling tick to the next.
     *
     * <p>Pids appearing for the first time contribute 0 this tick (avoids the
     * initial cumulative burst); dead pids are dropped from the carried-over map.
     * The idle budget is {@code elapsedMs * clockTicks / 1000 * cores}; whatever
     * is not own-process busy time is reported as idle, since the rest of the
     * system is not observable without root (spec 010 RF-4).</p>
     *
     * @param prev Previous tick samples, keyed by pid, each {@code [utime, stime]}.
     * @param cur Current tick samples with the same shape.
     * @param elapsedMs Milliseconds elapsed between the two ticks.
     * @param cores Core count used for the idle budget.
     * @param clockTicks Kernel clock ticks per second ({@code sysconf(_SC_CLK_TCK)}).
     * @return Returns the busy/idle jiffies and the samples to carry over.
     */
    @NonNull
    public static CpuDelta advanceCpu(@Nullable Map<Integer, long[]> prev,
                                      @Nullable Map<Integer, long[]> cur,
                                      long elapsedMs, int cores, long clockTicks) {
        long busy = 0;
        if (cur != null && prev != null) {
            for (Map.Entry<Integer, long[]> entry : cur.entrySet()) {
                long[] previous = prev.get(entry.getKey());
                if (previous == null) continue;
                long delta = (entry.getValue()[0] + entry.getValue()[1])
                    - (previous[0] + previous[1]);
                if (delta > 0) busy += delta;
            }
        }
        long elapsedJiffies = elapsedMs * clockTicks / 1000;
        long idle = Math.max(0, elapsedJiffies * cores - busy);
        return new CpuDelta(busy, idle, cur == null ? new HashMap<>() : new HashMap<>(cur));
    }

    /**
     * Render the {@code /proc/stat} snapshot text for one sampling tick.
     *
     * <p>Per-core lines split the aggregate cpu line uniformly and carry the
     * remainders on the first cores so the sum of {@code cpuN} matches {@code cpu}
     * exactly (spec 010 RF-11). The optional {@code intr}/{@code ctxt}/
     * {@code softirq} lines are omitted: htop's parser treats them as optional.</p>
     *
     * @param cores Core count ({@code >= 1}); one {@code cpuN} line is emitted per core.
     * @param bootSec Boot time in seconds since epoch.
     * @param busy Busy jiffies of the interval.
     * @param idle Idle jiffies of the interval (must be already clamped via {@link #advanceCpu}).
     * @param running Runnable processes count.
     * @param blocked Processes in uninterruptible sleep count.
     * @param processesTotal Cumulative fork count of the synthetic history.
     * @return Returns the multi-line snapshot text (no trailing newline).
     */
    @NonNull
    public static String renderStat(int cores, long bootSec, long busy, long idle,
                                    int running, int blocked, long processesTotal) {
        StringBuilder builder = new StringBuilder();
        appendCpuLine(builder, "cpu", busy, idle);
        long perCoreBusy = busy / cores;
        long busyRemainder = busy % cores;
        long perCoreIdle = idle / cores;
        long idleRemainder = idle % cores;
        for (int i = 0; i < cores; i++) {
            long coreBusy = perCoreBusy + (i < busyRemainder ? 1 : 0);
            long coreIdle = perCoreIdle + (i < idleRemainder ? 1 : 0);
            appendCpuLine(builder, "cpu" + i, coreBusy, coreIdle);
        }
        builder.append("btime ").append(bootSec).append('\n');
        builder.append("processes ").append(processesTotal).append('\n');
        builder.append("procs_running ").append(running).append('\n');
        builder.append("procs_blocked ").append(blocked);
        return builder.toString();
    }

    private static void appendCpuLine(@NonNull StringBuilder builder, @NonNull String label,
                                      long busy, long idle) {
        builder.append(label);
        builder.append(' ').append(busy).append(" 0 0 ").append(idle);
        for (int i = 0; i < CPU_LINE_TRAILING_ZEROS; i++) // remaining fields are all 0
            builder.append(" 0");
        builder.append('\n');
    }

    /**
     * Render a minimal {@code /proc/meminfo} snapshot with the fields htop reads.
     *
     * @param totalKb Total memory in kB.
     * @param availableKb Available memory in kB (also reported as MemFree).
     * @return Returns the multi-line snapshot text (no trailing newline).
     */
    @NonNull
    public static String renderMeminfo(long totalKb, long availableKb) {
        return joinLines(
            infoLine("MemTotal", totalKb),
            infoLine("MemFree", availableKb),
            infoLine("MemAvailable", availableKb),
            infoLine("Buffers", 0),
            infoLine("Cached", 0),
            infoLine("SwapTotal", 0),
            infoLine("SwapFree", 0),
            infoLine("Shmem", 0),
            infoLine("SReclaimable", 0));
    }

    @NonNull
    private static String infoLine(@NonNull String label, long valueKb) {
        return String.format(Locale.ROOT, "%-15s%d kB", label + ':', valueKb);
    }

    /**
     * Render a {@code /proc/loadavg} snapshot line.
     *
     * @param l1 One minute load average.
     * @param l5 Five minutes load average.
     * @param l15 Fifteen minutes load average.
     * @param running Runnable processes count.
     * @param total Total processes count.
     * @return Returns the single line {@code x.xx y.yy z.zz running/total}.
     */
    @NonNull
    public static String renderLoadavg(double l1, double l5, double l15,
                                       int running, int total) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f %d/%d", l1, l5, l15, running, total);
    }

    /**
     * Choose the procfs files to overlay: every source path that is NOT readable
     * directly gets a {@code {host, guest}} pair for
     * {@code ProotShellEnvironment.buildProotCommand} (spec 010 RF-3). A full
     * legibility set returns an empty list (native passthrough, NFR-5).
     *
     * @param statRead Whether {@code /proc/stat} is directly readable.
     * @param memRead Whether {@code /proc/meminfo} is directly readable.
     * @param loadRead Whether {@code /proc/loadavg} is directly readable.
     * @param statOverlayPath Host path of the stat snapshot, or {@code null} if there will be no snapshot.
     * @param memOverlayPath Host path of the meminfo snapshot, or {@code null} if there will be no snapshot.
     * @param loadOverlayPath Host path of the loadavg snapshot, or {@code null} if there will be no snapshot.
     * @return Returns bind pairs as {@code String[]{host, guest}} only for the
     * blocked files whose snapshot path is non-null.
     */
    @NonNull
    public static List<String[]> overlays(boolean statRead, boolean memRead, boolean loadRead,
                                          @Nullable String statOverlayPath,
                                          @Nullable String memOverlayPath,
                                          @Nullable String loadOverlayPath) {
        List<String[]> binds = new ArrayList<>();
        if (!statRead && statOverlayPath != null)
            binds.add(new String[]{statOverlayPath, "/proc/stat"});
        if (!memRead && memOverlayPath != null)
            binds.add(new String[]{memOverlayPath, "/proc/meminfo"});
        if (!loadRead && loadOverlayPath != null)
            binds.add(new String[]{loadOverlayPath, "/proc/loadavg"});
        return binds;
    }

    /**
     * Derive the kernel boot time (seconds since epoch) from wall clock and uptime.
     *
     * @param nowMs Current wall clock milliseconds.
     * @param uptimeMs Uptime milliseconds since boot (e.g. {@code SystemClock.elapsedRealtime()}).
     * @return Returns boot time in seconds, rounded, with ≤1 s of error (NFR-2 allows ≤2 s).
     */
    public static long btimeFromBoot(long nowMs, long uptimeMs) {
        return Math.round((nowMs - uptimeMs) / 1000.0d);
    }

    @NonNull
    private static String joinLines(@NonNull String... lines) {
        return String.join("\n", lines);
    }
}
