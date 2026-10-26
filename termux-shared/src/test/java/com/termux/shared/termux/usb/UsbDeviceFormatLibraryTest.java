package com.termux.shared.termux.usb;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link UsbDeviceFormatLibrary} (spec 020, T1).
 */
public class UsbDeviceFormatLibraryTest {

    @Test
    public void testFormatLsusbLine_fullDetails() {
        String line = UsbDeviceFormatLibrary.formatLsusbLine(1, 2, 0x1a86, 0x7523, "QinHeng Electronics", "CH340 serial converter");
        Assert.assertEquals("Bus 001 Device 002: ID 1a86:7523 QinHeng Electronics CH340 serial converter", line);
    }

    @Test
    public void testFormatLsusbLine_singleDigitPadding() {
        String line = UsbDeviceFormatLibrary.formatLsusbLine(1, 5, 0x05, 0x09, null, "USB Mouse");
        Assert.assertEquals("Bus 001 Device 005: ID 0005:0009 USB Mouse", line);
    }

    @Test
    public void testFormatLsusbLine_noDescription() {
        String line = UsbDeviceFormatLibrary.formatLsusbLine(2, 10, 0x1234, 0x5678, null, null);
        Assert.assertEquals("Bus 002 Device 010: ID 1234:5678", line);
    }

    @Test
    public void testFormatLsusbLine_manufacturerOnly() {
        String line = UsbDeviceFormatLibrary.formatLsusbLine(1, 3, 0x046d, 0xc52b, "Logitech, Inc.", null);
        Assert.assertEquals("Bus 001 Device 003: ID 046d:c52b Logitech, Inc.", line);
    }

    @Test
    public void testFormatRootHubLsusbLine() {
        String line = UsbDeviceFormatLibrary.formatRootHubLsusbLine(1);
        Assert.assertEquals("Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub", line);
    }

    @Test
    public void testParseDevicePath_validPaths() {
        int[] result1 = UsbDeviceFormatLibrary.parseDevicePath("/dev/bus/usb/001/002");
        Assert.assertNotNull(result1);
        Assert.assertEquals(1, result1[0]);
        Assert.assertEquals(2, result1[1]);

        int[] result2 = UsbDeviceFormatLibrary.parseDevicePath("002/015");
        Assert.assertNotNull(result2);
        Assert.assertEquals(2, result2[0]);
        Assert.assertEquals(15, result2[1]);
    }

    @Test
    public void testParseDevicePath_invalidPaths() {
        Assert.assertNull(UsbDeviceFormatLibrary.parseDevicePath(null));
        Assert.assertNull(UsbDeviceFormatLibrary.parseDevicePath(""));
        Assert.assertNull(UsbDeviceFormatLibrary.parseDevicePath("/dev/bus/usb/abc/def"));
        Assert.assertNull(UsbDeviceFormatLibrary.parseDevicePath("/dev/bus/usb/001"));
        Assert.assertNull(UsbDeviceFormatLibrary.parseDevicePath("/dev/null"));
    }

    @Test
    public void testBuildDevicePath() {
        String path = UsbDeviceFormatLibrary.buildDevicePath(1, 2);
        Assert.assertEquals("/dev/bus/usb/001/002", path);
    }

    @Test
    public void testJsonSerialization_roundtrip() {
        List<UsbDeviceFormatLibrary.UsbDeviceInfo> devices = new ArrayList<>();
        devices.add(new UsbDeviceFormatLibrary.UsbDeviceInfo(
            "/dev/bus/usb/001/002",
            1, 2, 0x1a86, 0x7523,
            "QinHeng Electronics", "CH340",
            0, 0, 0
        ));
        devices.add(new UsbDeviceFormatLibrary.UsbDeviceInfo(
            "/dev/bus/usb/001/003",
            1, 3, 0x046d, 0xc52b,
            "Logitech", "Unifying Receiver",
            3, 1, 1
        ));

        String json = UsbDeviceFormatLibrary.toJson(devices);
        Assert.assertNotNull(json);

        List<UsbDeviceFormatLibrary.UsbDeviceInfo> parsed = UsbDeviceFormatLibrary.fromJson(json);
        Assert.assertNotNull(parsed);
        Assert.assertEquals(2, parsed.size());

        UsbDeviceFormatLibrary.UsbDeviceInfo d1 = parsed.get(0);
        Assert.assertEquals("/dev/bus/usb/001/002", d1.path);
        Assert.assertEquals(1, d1.busNumber);
        Assert.assertEquals(2, d1.deviceNumber);
        Assert.assertEquals(0x1a86, d1.vendorId);
        Assert.assertEquals(0x7523, d1.productId);
        Assert.assertEquals("QinHeng Electronics", d1.manufacturer);
        Assert.assertEquals("CH340", d1.productName);

        UsbDeviceFormatLibrary.UsbDeviceInfo d2 = parsed.get(1);
        Assert.assertEquals("/dev/bus/usb/001/003", d2.path);
        Assert.assertEquals(0x046d, d2.vendorId);
        Assert.assertEquals(0xc52b, d2.productId);
        Assert.assertEquals("Logitech", d2.manufacturer);
        Assert.assertEquals("Unifying Receiver", d2.productName);
    }

    @Test
    public void testJsonSerialization_nullAndEmpty() {
        Assert.assertEquals("[]", UsbDeviceFormatLibrary.toJson(null));
        Assert.assertEquals("[]", UsbDeviceFormatLibrary.toJson(new ArrayList<>()));

        List<UsbDeviceFormatLibrary.UsbDeviceInfo> listNull = UsbDeviceFormatLibrary.fromJson(null);
        Assert.assertNotNull(listNull);
        Assert.assertTrue(listNull.isEmpty());

        List<UsbDeviceFormatLibrary.UsbDeviceInfo> listInvalid = UsbDeviceFormatLibrary.fromJson("not-json");
        Assert.assertNotNull(listInvalid);
        Assert.assertTrue(listInvalid.isEmpty());
    }
}
