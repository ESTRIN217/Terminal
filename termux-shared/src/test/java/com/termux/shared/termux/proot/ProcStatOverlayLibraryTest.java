package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProcStatOverlayLibraryTest {

    // ---- advanceCpu ----

    @Test
    public void testAdvanceCpu_deltasAndIdle_4cores() {
        Map<Integer, long[]> prev = mapOf(1, 100L, 50L, 2, 0L, 0L);
        Map<Integer, long[]> cur = mapOf(1, 150L, 100L, 2, 10L, 0L);

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            prev, cur, 1000, 4, 100);

        // busy = (150+100) - (100+50) + (10+0) - 0 = 110
        Assert.assertEquals(110, delta.busy);
        // idle = 1000ms * 100/1000 * 4 cores - 110 = 400 - 110 = 290
        Assert.assertEquals(290, delta.idle);
        // next carries the current samples for the following tick
        assertSamplesEquals(mapOf(1, 150L, 100L, 2, 10L, 0L), delta.next);
    }

    @Test
    public void testAdvanceCpu_newPidContributesZeroAndIsKept() {
        Map<Integer, long[]> prev = mapOf(1, 100L, 50L);
        Map<Integer, long[]> cur = mapOf(1, 100L, 50L, 3, 999L, 999L);

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            prev, cur, 1000, 4, 100);

        Assert.assertEquals(0, delta.busy);
        Assert.assertEquals(400, delta.idle);
        Assert.assertTrue(delta.next.containsKey(3));
    }

    @Test
    public void testAdvanceCpu_deadPidDiscarded() {
        Map<Integer, long[]> prev = mapOf(1, 100L, 50L, 2, 5L, 5L);
        Map<Integer, long[]> cur = mapOf(1, 120L, 60L);

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            prev, cur, 1000, 4, 100);

        // busy = (120+60) - (100+50) = 30; pid 2 is gone
        Assert.assertEquals(30, delta.busy);
        Assert.assertFalse(delta.next.containsKey(2));
    }

    @Test
    public void testAdvanceCpu_idleClampedToZero() {
        Map<Integer, long[]> prev = mapOf(1, 100L, 50L);
        Map<Integer, long[]> cur = mapOf(1, 600L, 550L);

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            prev, cur, 1000, 4, 100);

        // busy = 1000 > 400 available jiffies -> idle clamps to 0
        Assert.assertEquals(1000, delta.busy);
        Assert.assertEquals(0, delta.idle);
    }

    @Test
    public void testAdvanceCpu_partialSecondFloors() {
        Map<Integer, long[]> prev = mapOf(1, 100L, 50L);
        Map<Integer, long[]> cur = mapOf(1, 105L, 55L);

        ProcStatOverlayLibrary.CpuDelta delta = ProcStatOverlayLibrary.advanceCpu(
            prev, cur, 1500, 1, 100);

        // 1500ms * 100/1000 = 150 total jiffies; busy = 10; idle = 140
        Assert.assertEquals(10, delta.busy);
        Assert.assertEquals(140, delta.idle);
    }

    // ---- renderStat ----

    @Test
    public void testRenderStat_singleCore() {
        String stat = ProcStatOverlayLibrary.renderStat(1, 1000, 55, 145, 2, 0, 42);

        List<String> lines = Arrays.asList(stat.split("\n", -1));
        Assert.assertEquals(6, lines.size());
        Assert.assertEquals("cpu 55 0 0 145 0 0 0 0 0 0", lines.get(0));
        Assert.assertEquals("cpu0 55 0 0 145 0 0 0 0 0 0", lines.get(1));
        Assert.assertEquals("btime 1000", lines.get(2));
        Assert.assertEquals("processes 42", lines.get(3));
        Assert.assertEquals("procs_running 2", lines.get(4));
        Assert.assertEquals("procs_blocked 0", lines.get(5));
    }

    @Test
    public void testRenderStat_twoCoresUniformSplitWithRemainder() {
        // busy=105, idle=190, 2 cores: cpu0 gets the +1 remainder on user
        String stat = ProcStatOverlayLibrary.renderStat(2, 700, 105, 190, 3, 1, 9);

        List<String> lines = Arrays.asList(stat.split("\n", -1));
        Assert.assertEquals("cpu 105 0 0 190 0 0 0 0 0 0", lines.get(0));
        Assert.assertEquals("cpu0 53 0 0 95 0 0 0 0 0 0", lines.get(1));
        Assert.assertEquals("cpu1 52 0 0 95 0 0 0 0 0 0", lines.get(2));
        Assert.assertEquals("btime 700", lines.get(3));
        Assert.assertEquals("processes 9", lines.get(4));
        Assert.assertEquals("procs_running 3", lines.get(5));
        Assert.assertEquals("procs_blocked 1", lines.get(6));

        assertPerCoreSumMatchesTotal(lines);
    }

    @Test
    public void testRenderStat_eightCoresUniformSplit() {
        String stat = ProcStatOverlayLibrary.renderStat(8, 0, 84, 776, 1, 0, 0);

        // busy/8 = 10 remaining 4 -> first 4 cores get 11; idle 97 each
        List<String> lines = Arrays.asList(stat.split("\n", -1));
        Assert.assertEquals("cpu0 11 0 0 97 0 0 0 0 0 0", lines.get(1));
        Assert.assertEquals("cpu3 11 0 0 97 0 0 0 0 0 0", lines.get(4));
        Assert.assertEquals("cpu4 10 0 0 97 0 0 0 0 0 0", lines.get(5));
        Assert.assertEquals("cpu7 10 0 0 97 0 0 0 0 0 0", lines.get(8));

        assertPerCoreSumMatchesTotal(lines);
    }

    // ---- renderMeminfo ----

    @Test
    public void testRenderMeminfo_fieldsAndKbUnits() {
        String meminfo = ProcStatOverlayLibrary.renderMeminfo(3800000, 2450000);

        List<String> lines = Arrays.asList(meminfo.split("\n", -1));
        Assert.assertEquals(9, lines.size());
        Assert.assertEquals("MemTotal:      3800000 kB", lines.get(0));
        Assert.assertEquals("MemFree:       2450000 kB", lines.get(1));
        Assert.assertEquals("MemAvailable:  2450000 kB", lines.get(2));
        Assert.assertEquals("Buffers:       0 kB", lines.get(3));
        Assert.assertEquals("Cached:        0 kB", lines.get(4));
        Assert.assertEquals("SwapTotal:     0 kB", lines.get(5));
        Assert.assertEquals("SwapFree:      0 kB", lines.get(6));
        Assert.assertEquals("Shmem:         0 kB", lines.get(7));
        Assert.assertEquals("SReclaimable:  0 kB", lines.get(8));
    }

    // ---- renderLoadavg ----

    @Test
    public void testRenderLoadavg_twoDecimalsAndCounts() {
        Assert.assertEquals("1.23 0.46 6.70 2/345",
            ProcStatOverlayLibrary.renderLoadavg(1.234, 0.456, 6.7, 2, 345));
    }

    // ---- btimeFromBoot ----

    @Test
    public void testBtimeFromBoot() {
        Assert.assertEquals(1, ProcStatOverlayLibrary.btimeFromBoot(30500, 30000));
        Assert.assertEquals(0, ProcStatOverlayLibrary.btimeFromBoot(30499, 30000));
        Assert.assertEquals(30, ProcStatOverlayLibrary.btimeFromBoot(60000, 30000));
    }

    // ---- overlays (RF-3/NFR-5) ----

    private static final String STAT_PATH = "/host/stat";
    private static final String MEM_PATH = "/host/meminfo";
    private static final String LOAD_PATH = "/host/loadavg";

    @Test
    public void testOverlays_allReadable_nativePassthrough() {
        Assert.assertTrue(ProcStatOverlayLibrary.overlays(true, true, true,
            STAT_PATH, MEM_PATH, LOAD_PATH).isEmpty());
    }

    @Test
    public void testOverlays_allBlocked_bindsInOrder() {
        List<String[]> binds = ProcStatOverlayLibrary.overlays(false, false, false,
            STAT_PATH, MEM_PATH, LOAD_PATH);

        Assert.assertEquals(3, binds.size());
        Assert.assertArrayEquals(new String[]{STAT_PATH, "/proc/stat"}, binds.get(0));
        Assert.assertArrayEquals(new String[]{MEM_PATH, "/proc/meminfo"}, binds.get(1));
        Assert.assertArrayEquals(new String[]{LOAD_PATH, "/proc/loadavg"}, binds.get(2));
    }

    @Test
    public void testOverlays_partial_onlyBlockedOnes() {
        // Only stat is blocked
        List<String[]> statOnly = ProcStatOverlayLibrary.overlays(false, true, true,
            STAT_PATH, MEM_PATH, LOAD_PATH);
        Assert.assertEquals(1, statOnly.size());
        Assert.assertArrayEquals(new String[]{STAT_PATH, "/proc/stat"}, statOnly.get(0));

        // Only loadavg is blocked
        List<String[]> loadOnly = ProcStatOverlayLibrary.overlays(true, true, false,
            STAT_PATH, MEM_PATH, LOAD_PATH);
        Assert.assertEquals(1, loadOnly.size());
        Assert.assertArrayEquals(new String[]{LOAD_PATH, "/proc/loadavg"}, loadOnly.get(0));
    }

    @Test
    public void testOverlays_blockedWithoutSnapshotPath_isSkipped() {
        // A blocked file with a null snapshot path cannot be served: no bind for it.
        List<String[]> binds = ProcStatOverlayLibrary.overlays(false, true, false,
            STAT_PATH, null, LOAD_PATH);
        Assert.assertEquals(2, binds.size());
        Assert.assertArrayEquals(new String[]{STAT_PATH, "/proc/stat"}, binds.get(0));
        Assert.assertArrayEquals(new String[]{LOAD_PATH, "/proc/loadavg"}, binds.get(1));
    }

    // ---- helpers ----

    /** Parses the per-core lines and asserts their user/idle sums equal the aggregate `cpu` line. */
    private static void assertPerCoreSumMatchesTotal(List<String> lines) {
        String[] total = lines.get(0).split("\\s+");
        long totalUser = Long.parseLong(total[1]);
        long totalIdle = Long.parseLong(total[4]);

        long sumUser = 0, sumIdle = 0;
        for (String line : lines) {
            if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue;
            String[] parts = line.split("\\s+");
            sumUser += Long.parseLong(parts[1]);
            sumIdle += Long.parseLong(parts[4]);
        }
        Assert.assertEquals(totalUser, sumUser);
        Assert.assertEquals(totalIdle, sumIdle);
    }

    private static Map<Integer, long[]> mapOf(Object... raw) {
        Map<Integer, long[]> map = new HashMap<>();
        for (int i = 0; i < raw.length; i += 3)
            map.put((Integer) raw[i], new long[]{(Long) raw[i + 1], (Long) raw[i + 2]});
        return map;
    }

    /** Compares two sample maps by key and by long[] content (JUnit's assertEquals compares long[] by identity). */
    private static void assertSamplesEquals(Map<Integer, long[]> expected, Map<Integer, long[]> actual) {
        Assert.assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<Integer, long[]> entry : expected.entrySet())
            Assert.assertArrayEquals(entry.getValue(), actual.get(entry.getKey()));
    }
}
