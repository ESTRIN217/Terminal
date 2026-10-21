package com.termux.shared.termux.proot;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the synthetic procfs snapshots of spec 010 alive: samples the app's own
 * processes ({@code /proc/<pid>/stat}, the only per-process data Android exposes
 * without root), {@code SystemClock} for the boot time and
 * {@code ActivityManager.MemoryInfo} for the memory, then rewrites the snapshot
 * files atomically (temp file + rename, RF-7) every second while there is at
 * least one proot session active (RF-6, NFR-3/NFR-4).
 *
 * <p>All rendering and delta math is delegated to the pure
 * {@link ProcStatOverlayLibrary}. Lifecycle counters are static because the
 * sampler is a process-wide singleton owned by {@code TermuxService}.</p>
 */
public final class ProcStatOverlaySampler {

    private static final String LOG_TAG = "ProcStatOverlaySampler";

    /** Snapshot refresh period; htop polls every 1.5 s by default, so the meters stay alive (NFR-3). */
    private static final long SAMPLE_PERIOD_MS = 1000;
    /** Exponential decays for the 1/5/15 minutes synthetic load averages. */
    private static final double[] LOAD_DECAY = {0.8, 0.95, 0.98};
    /** Host paths of the procfs sources handled by the overlay (spec 010 RF-3). */
    private static final String HOST_PROC_STAT_PATH = "/proc/stat";
    private static final String HOST_PROC_MEMINFO_PATH = "/proc/meminfo";
    private static final String HOST_PROC_LOADAVG_PATH = "/proc/loadavg";

    /** Samples read from {@code /proc/<pid>/stat} for a single pid. */
    public static final class PidSample {

        /** Process state character from the stat file (R, S, D, I, Z, T, ...). */
        public final char state;
        /** Clock ticks spent in user mode since the process started. */
        public final long utime;
        /** Clock ticks spent in system (kernel) mode since the process started. */
        public final long stime;

        /**
         * @param state State character (stat field 3).
         * @param utime User mode clock ticks (stat field 14).
         * @param stime System mode clock ticks (stat field 15).
         */
        public PidSample(char state, long utime, long stime) {
            this.state = state;
            this.utime = utime;
            this.stime = stime;
        }

        /**
         * Parse a {@code /proc/<pid>/stat} line into a sample.
         *
         * <p>The comm field may contain spaces and parenthesis, so parsing must
         * resume after the LAST ')'. The fields after the state are ppid, pgrp,
         * session, tty_nr, tpgid, flags, minflt, cminflt, majflt, cmajflt (10
         * values) and then utime, stime.</p>
         *
         * @param line The raw stat line.
         * @return Returns the parsed sample, or {@code null} when the line is
         * malformed or misses the cpu time fields.
         */
        @Nullable
        public static PidSample parse(@Nullable String line) {
            if (line == null) return null;
            int stateStart = line.lastIndexOf(')');
            if (stateStart == -1 || stateStart + 2 >= line.length()) return null;
            String[] fields = line.substring(stateStart + 1).trim().split("\\s+");
            // fields[0] is the state (stat field 3); utime is field 14
            // (fields[11]) and stime is field 15 (fields[12]).
            if (fields.length < 13) return null;
            try {
                long utime = Long.parseLong(fields[11]);
                long stime = Long.parseLong(fields[12]);
                return new PidSample(fields[0].charAt(0), utime, stime);
            } catch (NumberFormatException e) {
                Logger.logError(LOG_TAG, "Failed to parse /proc/<pid>/stat cpu times: " + e);
                return null;
            }
        }
    }

    /** Active singleton; {@code null} while no overlay is needed. */
    @Nullable
    private static ProcStatOverlaySampler sSampler;
    /** Count of active proot sessions requesting the overlay (service-managed). */
    private static int sOverlaySessionCount = 0;

    /** App context needed for {@code ActivityManager} (device wake-up storage-safe: app context). */
    @Nullable
    private final Context mContext;
    /** Scheduled tick executor; a new one is created for every start. */
    @Nullable
    private ScheduledExecutorService mExecutor;
    /** Handle of the periodic sampling tasks. */
    @Nullable
    private ScheduledFuture<?> mFuture;

    private final boolean mStatOverlayNeeded;
    private final boolean mMemOverlayNeeded;
    private final boolean mLoadOverlayNeeded;
    /** Whether the host {@code /proc/loadavg} itself is readable (real procs running/blocked). */
    private final boolean mHostLoadavgReadable;

    /** Previous tick's utime/stime samples, keyed by pid, for the delta computation. */
    @NonNull
    private final Map<Integer, long[]> mPrevCpuTimes = new HashMap<>();
    /** Pid keys seen in the previous tick, to count nets of new pids for the fork counter. */
    @NonNull
    private Set<Integer> mPrevSamplePids = new HashSet<>();
    /** 1/5/15 minutes exponentially decayed synthetic load averages. */
    private final double[] mLoads = new double[LOAD_DECAY.length];
    /** Cumulative fork count of the synthetic history. */
    private long mProcessesTotal;
    /** Wall clock of the previous tick (uptime ms); 0 before the first tick. */
    private long mPreviousTickMs;

    private ProcStatOverlaySampler(@Nullable Context context, boolean statOverlayNeeded,
                                   boolean memOverlayNeeded, boolean loadOverlayNeeded,
                                   boolean hostLoadavgReadable) {
        mContext = context;
        mStatOverlayNeeded = statOverlayNeeded;
        mMemOverlayNeeded = memOverlayNeeded;
        mLoadOverlayNeeded = loadOverlayNeeded;
        mHostLoadavgReadable = hostLoadavgReadable;
    }

    /**
     * Prepare the overlay for a new proot session: probe the direct readability
     * of the procfs sources, ensure the snapshot directory, write the initial
     * snapshots synchronously and start (or keep) the sampler.
     *
     * @param context App context for {@code FileUtils} and {@code ActivityManager}.
     * @return Returns the {@code {host, guest}} bind pairs for
     * {@code ProotShellEnvironment.buildProotCommand}, or {@code null} when
     * everything is directly readable and no overlay applies (NFR-5).
     */
    @Nullable
    public static synchronized List<String[]> prepareSessionOverlayBinds(@NonNull Context context) {
        boolean statRead = isDirectlyReadable(HOST_PROC_STAT_PATH);
        boolean memRead = isDirectlyReadable(HOST_PROC_MEMINFO_PATH);
        boolean loadRead = isDirectlyReadable(HOST_PROC_LOADAVG_PATH);

        if (statRead && memRead && loadRead)
            return null; // nothing is blocked: pure native passthrough (RF-9b, NFR-5)

        Error directoryError = FileUtils.createDirectoryFile(
            TermuxConstants.DEBIAN_PROC_OVERLAY_DIR_PATH);
        if (directoryError != null) {
            // Degraded mode: log and continue; snapshot writes will fail visibly
            // in the logs if the directory stays broken, sessions keep working.
            Logger.logErrorExtended(LOG_TAG, "Failed to create the proc overlay directory\n"
                + directoryError);
        }

        if (sSampler == null) {
            sSampler = new ProcStatOverlaySampler(
                context.getApplicationContext(), !statRead, !memRead, !loadRead, loadRead);
            sSampler.sampleOnce(); // synchronous initial snapshot before the guest starts
            sSampler.startExecutor();
        }
        sOverlaySessionCount++;

        return ProcStatOverlayLibrary.overlays(statRead, memRead, loadRead,
            TermuxConstants.DEBIAN_PROC_OVERLAY_STAT_FILE_PATH,
            TermuxConstants.DEBIAN_PROC_OVERLAY_MEMINFO_FILE_PATH,
            TermuxConstants.DEBIAN_PROC_OVERLAY_LOADAVG_FILE_PATH);
    }

    /**
     * Bookkeeping of a removed proot session that was served an overlay. Stops
     * the sampler when the last overlay session is gone (RF-6).
     */
    public static synchronized void onProotSessionRemoved() {
        if (sOverlaySessionCount > 0) sOverlaySessionCount--;
        if (sOverlaySessionCount == 0 && sSampler != null) {
            sSampler.stopExecutor();
            sSampler = null;
        }
    }

    /** Whether the sampler is currently running (for lifecycle checks). */
    public static synchronized boolean isSampling() {
        return sSampler != null;
    }

    /**
     * Probe a procfs path for direct readability by this app's uid by actually
     * opening it: SELinux denials surface as {@code IOException}/{@code
     * SecurityException} per spec 010 RF-3 (never assumed at face value).
     *
     * @param path Host path to probe.
     * @return Returns {@code true} if the path could be opened for reading.
     */
    public static boolean isDirectlyReadable(@NonNull String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            in.read();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private void startExecutor() {        mExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "proc-stat-overlay-sampler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        mFuture = mExecutor.scheduleWithFixedDelay(this::sampleOnceSafely,
            SAMPLE_PERIOD_MS, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void stopExecutor() {
        if (mFuture != null) mFuture.cancel(false);
        if (mExecutor != null) mExecutor.shutdown();
        mExecutor = null;
        mFuture = null;
    }

    /** Tick: sample the sources, advance bookkeeping and rewrite the snapshots (RF-2). */
    private void sampleOnceSafely() {
        try {
            sampleOnce();
        } catch (Throwable throwable) {
            // Sampling must never take the process down: log and keep ticking.
            Logger.logErrorExtended(LOG_TAG, "Proc stat overlay tick failed\n"
                + Logger.getStackTraceString(throwable));
        }
    }

    private void sampleOnce() {
        long clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK);
        int cores = Runtime.getRuntime().availableProcessors();
        long bootSec = ProcStatOverlayLibrary.btimeFromBoot(
            System.currentTimeMillis(), SystemClock.elapsedRealtime());

        long tickStartMs = SystemClock.uptimeMillis();
        Map<Integer, PidSample> samples = scanOwnPidSamples();

        Map<Integer, long[]> currentCpuTimes = new HashMap<>();
        for (Map.Entry<Integer, PidSample> entry : samples.entrySet())
            currentCpuTimes.put(entry.getKey(), new long[]{entry.getValue().utime, entry.getValue().stime});

        long elapsedMs = mPreviousTickMs == 0 ? SAMPLE_PERIOD_MS : tickStartMs - mPreviousTickMs;
        mPreviousTickMs = tickStartMs;

        // Pids that appeared during this interval count toward the synthetic fork
        // counter (the first tick only seeds the counter).
        if (mPrevSamplePids.isEmpty()) {
            mProcessesTotal = currentCpuTimes.size();
        } else {
            int newPids = 0;
            for (Integer pid : currentCpuTimes.keySet())
                if (!mPrevSamplePids.contains(pid)) newPids++;
            mProcessesTotal += newPids;
        }
        mPrevSamplePids = new HashSet<>(currentCpuTimes.keySet());

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            mPrevCpuTimes, currentCpuTimes, elapsedMs, cores, clockTicks);
        mPrevCpuTimes.clear();
        mPrevCpuTimes.putAll(delta.next);

        int running;
        int blocked;
        if (mHostLoadavgReadable) {
            int[] counts = parseHostLoadavgCounts();
            running = counts == null ? countOfState(samples, 'R') : counts[0];
            blocked = counts == null ? countOfState(samples, 'D') : counts[1];
        } else {
            running = countOfState(samples, 'R');
            blocked = countOfState(samples, 'D');
        }

        if (mStatOverlayNeeded)
            writeSnapshotAtomic(TermuxConstants.DEBIAN_PROC_OVERLAY_STAT_FILE_PATH,
                ProcStatOverlayLibrary.renderStat(cores, bootSec, delta.busy, delta.idle,
                    running, blocked, mProcessesTotal) + "\n");

        if (mMemOverlayNeeded) {
            long[] memoryKb = readMemoryKb();
            if (memoryKb != null)
                writeSnapshotAtomic(TermuxConstants.DEBIAN_PROC_OVERLAY_MEMINFO_FILE_PATH,
                    ProcStatOverlayLibrary.renderMeminfo(memoryKb[0], memoryKb[1]) + "\n");
        }

        if (mLoadOverlayNeeded) {
            double busyLoad = elapsedMs > 0
                ? (double) delta.busy / (clockTicks * cores * elapsedMs / 1000.0d) : 0;
            for (int i = 0; i < LOAD_DECAY.length; i++)
                mLoads[i] = LOAD_DECAY[i] * mLoads[i]
                    + (1 - LOAD_DECAY[i]) * Math.min(cores, busyLoad);
            writeSnapshotAtomic(TermuxConstants.DEBIAN_PROC_OVERLAY_LOADAVG_FILE_PATH,
                ProcStatOverlayLibrary.renderLoadavg(mLoads[0], mLoads[1], mLoads[2],
                    running, samples.size()) + "\n");
        }
    }

    /**
     * Count the samples whose state equals {@code wanted}.
     *
     * @param samples Samples of the current tick.
     * @param wanted State character to count (e.g. {@code 'R'} run queue, {@code 'D'} blocked).
     * @return Returns the count.
     */
    public static int countOfState(@NonNull Map<Integer, PidSample> samples, char wanted) {
        int count = 0;
        for (PidSample sample : samples.values())
            if (sample != null && sample.state == wanted) count++;
        return count;
    }

    /**
     * Read the stat line of every listed pid the app can access: other uids are
     * expected to be unreadable (hidepid) and those entries are skipped silently.
     *
     * @return Returns samples keyed by pid for all parseable entries.
     */
    @NonNull
    static Map<Integer, PidSample> scanOwnPidSamples() {
        Map<Integer, PidSample> samples = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return samples;
        for (File entry : entries) {
            Integer pid = parsePid(entry.getName());
            if (pid == null || !entry.isDirectory()) continue;
            try (FileInputStream in = new FileInputStream(new File(entry, "stat"))) {
                byte[] bytes = new byte[8192];
                int read = in.read(bytes, 0, bytes.length);
                if (read <= 0) continue;
                PidSample sample = PidSample.parse(new String(bytes, 0, read, StandardCharsets.UTF_8));
                if (sample != null) samples.put(pid, sample);
            } catch (IOException | SecurityException e) {
                // Another uid's entry or a process that died mid-read: skip silently.
            }
        }
        return samples;
    }

    @Nullable
    private static Integer parsePid(@Nullable String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse the 4th host loadavg field ({@code running/total}) into counts.
     *
     * @return Returns {@code {running, total}}, or {@code null} on unreadable or
     * malformed data.
     */
    @Nullable
    private static int[] parseHostLoadavgCounts() {
        try (FileInputStream in = new FileInputStream(HOST_PROC_LOADAVG_PATH)) {
            byte[] bytes = new byte[256];
            int read = in.read(bytes, 0, bytes.length);
            if (read <= 0) return null;
            String line = new String(bytes, 0, read, StandardCharsets.UTF_8).trim();
            String[] parts = line.split("\\s+");
            if (parts.length < 4) return null;
            String[] counts = parts[3].split("/");
            if (counts.length != 2) return null;
            return new int[]{Integer.parseInt(counts[0]), Integer.parseInt(counts[1])};
        } catch (IOException | SecurityException | IndexOutOfBoundsException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Memory snapshot values via the Android API (spec 010 RF-4), in kilobytes.
     *
     * @return Returns {@code {totalKb, availableKb}}, or {@code null} if the
     * {@code ActivityManager} did not report them.
     */
    @Nullable
    private long[] readMemoryKb() {
        if (mContext == null) return null;
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager == null) return null;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(info);
        if (info.totalMem <= 0) return null;
        return new long[]{info.totalMem / 1024L, Math.max(0L, info.availMem) / 1024L};
    }

    /**
     * Write a snapshot as a whole: temp file first, then rename over the target,
     * so htop never parses half a snapshot (RF-7).
     *
     * @param path Target snapshot path.
     * @param content Snapshot content (newline-terminated by the caller).
     */
    private static void writeSnapshotAtomic(@NonNull String path, @NonNull String content) {
        File target = new File(path);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Logger.logErrorExtended(LOG_TAG, String.format(Locale.ROOT,
                "Failed to write snapshot temp file: %s (%s)", temp, e));
            if (!temp.delete()) Logger.logError(LOG_TAG, "Failed to delete temp file: " + temp);
            return;
        }
        if (!temp.renameTo(target))
            Logger.logErrorExtended(LOG_TAG, "Failed to replace snapshot file: " + target);
    }
}
