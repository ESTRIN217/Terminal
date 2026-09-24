package com.termux.shared.terminal;

import org.junit.Assert;
import org.junit.Test;

/** Feature Reporting string encoding for {@code TERM_FEATURES}. */
public class TerminalFeatureReportTest {

    @Test
    public void build_bothOn_includesFileAndHyperlinks() {
        Assert.assertEquals("T3BMHF", TerminalFeatureReport.buildFeatureString(true, true));
    }

    @Test
    public void build_imagesOff_omitsFile() {
        Assert.assertEquals("T3BMH", TerminalFeatureReport.buildFeatureString(false, true));
    }

    @Test
    public void build_hyperlinksOff_omitsHyperlinks() {
        Assert.assertEquals("T3BMF", TerminalFeatureReport.buildFeatureString(true, false));
    }

    @Test
    public void build_bothOff_isBare() {
        Assert.assertEquals("T3BM", TerminalFeatureReport.buildFeatureString(false, false));
    }

    @Test
    public void build_neverAdvertisesSixel() {
        final String s = TerminalFeatureReport.buildFeatureString(true, true);
        Assert.assertFalse(s.contains("Sx"));
        Assert.assertTrue(s.startsWith("T3"));
    }
}
