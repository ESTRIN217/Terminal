package com.termux.shared.termux.proot;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ProcStatOverlaySamplerTest {

    // ---- PidSample.parse (RF-4: carga propia de /proc/<pid>/stat) ----

    @Test
    public void testParsePidSample_standardLine() {
        // Fields after the state: ppid pgrp session tty_nr tpgid flags minflt
        // cminflt majflt cmajflt (10) then utime stime.
        ProcStatOverlaySampler.PidSample sample = ProcStatOverlaySampler.PidSample.parse(
            "4711 (bash) R 4709 2 2 0 -1 0 0 0 0 0 101 14 0 123 4711 42 3 0 0 1");
        Assert.assertNotNull(sample);
        Assert.assertEquals('R', sample.state);
        Assert.assertEquals(101, sample.utime);
        Assert.assertEquals(14, sample.stime);
    }

    @Test
    public void testParsePidSample_commWithSpacesAndParens() {
        // The comm may contain spaces and ')': the state must come after the LAST ')'.
        ProcStatOverlaySampler.PidSample sample = ProcStatOverlaySampler.PidSample.parse(
            "12 (a )b)S 2 3 4 5 6 7 8 9 0 1 52 44 0 0 0 0");
        Assert.assertNotNull(sample);
        Assert.assertEquals('S', sample.state);
        Assert.assertEquals(52, sample.utime);
        Assert.assertEquals(44, sample.stime);
    }

    @Test
    public void testParsePidSample_invalidLines() {
        Assert.assertNull(ProcStatOverlaySampler.PidSample.parse("no rparen at all"));
        Assert.assertNull(ProcStatOverlaySampler.PidSample.parse("(name) R"));
        Assert.assertNull(ProcStatOverlaySampler.PidSample.parse("1 (x) R 2 3"));
        Assert.assertNull(ProcStatOverlaySampler.PidSample.parse("9 (x) S foo 0 0 0 1"));
    }

    // ---- countOfState (procesos corriendo/bloqueados propios, RF-4) ----

    @Test
    public void testCountOfState() {
        Map<Integer, ProcStatOverlaySampler.PidSample> samples = new HashMap<>();
        samples.put(1, new ProcStatOverlaySampler.PidSample('R', 1, 0));
        samples.put(2, new ProcStatOverlaySampler.PidSample('R', 2, 1));
        samples.put(3, new ProcStatOverlaySampler.PidSample('S', 0, 0));
        samples.put(4, new ProcStatOverlaySampler.PidSample('D', 0, 0));

        Assert.assertEquals(2, ProcStatOverlaySampler.countOfState(samples, 'R'));
        Assert.assertEquals(1, ProcStatOverlaySampler.countOfState(samples, 'D'));
        Assert.assertEquals(1, ProcStatOverlaySampler.countOfState(samples, 'S'));
        Assert.assertEquals(0, ProcStatOverlaySampler.countOfState(samples, 'Z'));
    }
}
