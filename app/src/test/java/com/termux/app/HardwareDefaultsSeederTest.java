package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class HardwareDefaultsSeederTest {

    @Test
    public void testIsFreshInstall_allSignalsAbsentIsFresh() {
        Assert.assertTrue(HardwareDefaultsSeeder.isFreshInstall(true, false, false));
    }

    @Test
    public void testIsFreshInstall_existingWhenPreferencesWritten() {
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(false, false, false));
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(false, true, true));
    }

    @Test
    public void testIsFreshInstall_existingWhenRootfsInstalled() {
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(true, true, false));
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(true, true, true));
    }

    @Test
    public void testIsFreshInstall_existingWhenPropertiesFileExists() {
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(true, false, true));
        Assert.assertFalse(HardwareDefaultsSeeder.isFreshInstall(false, false, true));
    }

}
