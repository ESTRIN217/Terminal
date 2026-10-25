package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

/**
 * Pure JUnit tests for {@link TermuxInstaller} upgrading semantics of embedded
 * binaries (spec 018, Enmienda A, RF-05): a stale binary installed by a previous
 * APK must be replaced when the bundled asset differs, instead of being kept
 * forever ("already installed").
 */
public class TermuxInstallerTest {

    @Test
    public void binaryNeedsUpgrade_whenSizesDiffer() {
        Assert.assertTrue(TermuxInstaller.binaryNeedsUpgrade(1539848L, 1539896L));
    }

    @Test
    public void binaryNeedsUpgrade_whenInstalledShorterOrLonger() {
        Assert.assertTrue(TermuxInstaller.binaryNeedsUpgrade(0L, 1L));
        Assert.assertTrue(TermuxInstaller.binaryNeedsUpgrade(2L, 1L));
    }

    @Test
    public void binaryNeedsUpgrade_falseWhenSameSize() {
        Assert.assertFalse(TermuxInstaller.binaryNeedsUpgrade(1539896L, 1539896L));
    }

    @Test
    public void binaryNeedsUpgrade_zeroAssetMeansUpgrade() {
        // An unmeasurable asset (asset error) must not keep a stale binary silently.
        Assert.assertTrue(TermuxInstaller.binaryNeedsUpgrade(1L, 0L));
        Assert.assertTrue(TermuxInstaller.binaryNeedsUpgrade(0L, 0L));
    }
}
