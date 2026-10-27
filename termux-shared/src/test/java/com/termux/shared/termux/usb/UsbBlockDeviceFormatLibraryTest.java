package com.termux.shared.termux.usb;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link UsbBlockDeviceFormatLibrary} (spec 021, T4).
 */
public class UsbBlockDeviceFormatLibraryTest {

    // ------------------------------------------------------------------
    // isMassStorage (USB class 0x08) — RF-1
    // ------------------------------------------------------------------

    @Test
    public void testIsMassStorage_deviceClassMassStorage() {
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.isMassStorage(0x08, null));
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.isMassStorage(0x08, new int[]{0x03}));
    }

    @Test
    public void testIsMassStorage_interfaceClassMassStorage() {
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.isMassStorage(0x00, new int[]{0x08, 0x03}));
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.isMassStorage(0x00, new int[]{0x03, 0x08}));
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.isMassStorage(0xFF, new int[]{0x08}));
    }

    @Test
    public void testIsMassStorage_notMassStorage() {
        Assert.assertFalse(UsbBlockDeviceFormatLibrary.isMassStorage(0x00, new int[]{0x03, 0x02}));
        Assert.assertFalse(UsbBlockDeviceFormatLibrary.isMassStorage(0x09, new int[]{0x09}));
        Assert.assertFalse(UsbBlockDeviceFormatLibrary.isMassStorage(0x00, null));
        Assert.assertFalse(UsbBlockDeviceFormatLibrary.isMassStorage(0x00, new int[]{}));
    }

    // ------------------------------------------------------------------
    // formatCapacityBytes — integer sizes, no floats (RF-1)
    // ------------------------------------------------------------------

    @Test
    public void testFormatCapacityBytes_noFloats() {
        Assert.assertEquals("16G", UsbBlockDeviceFormatLibrary.formatCapacityBytes(17179869184L));
        Assert.assertEquals("14G", UsbBlockDeviceFormatLibrary.formatCapacityBytes(16000000000L));
        Assert.assertEquals("512M", UsbBlockDeviceFormatLibrary.formatCapacityBytes(536870912L));
        Assert.assertEquals("1000B", UsbBlockDeviceFormatLibrary.formatCapacityBytes(1000L));
        Assert.assertEquals("1023B", UsbBlockDeviceFormatLibrary.formatCapacityBytes(1023L));
        Assert.assertEquals("0B", UsbBlockDeviceFormatLibrary.formatCapacityBytes(0L));
        Assert.assertEquals("0B", UsbBlockDeviceFormatLibrary.formatCapacityBytes(-5L));
        String giant = UsbBlockDeviceFormatLibrary.formatCapacityBytes(1L << 40);
        Assert.assertEquals("1024G", giant);
    }

    // ------------------------------------------------------------------
    // formatLsblk — lsblk-ish table with limitation notice (RF-1, RF-2)
    // ------------------------------------------------------------------

    @Test
    public void testFormatLsblk_header() {
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(null);
        String[] lines = out.split("\n");
        Assert.assertEquals(3, lines.length);
        Assert.assertEquals("NAME      SIZE    TYPE          MOUNTPOINT", lines[0]);
        Assert.assertEquals("", lines[1]);
        Assert.assertTrue(lines[2].contains("(informational)"));
        Assert.assertTrue(lines[2].contains("/sys/dev/block"));
        Assert.assertTrue(lines[2].contains("SELinux"));
    }

    @Test
    public void testFormatLsblk_emptySnapshot() {
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(new ArrayList<>());
        Assert.assertTrue(out.contains("NAME      SIZE    TYPE          MOUNTPOINT"));
        Assert.assertEquals(3, out.split("\n").length);
    }

    @Test
    public void testFormatLsblk_singleDevice() {
        UsbBlockDeviceFormatLibrary.UsbBlockDevice dev =
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sda", 16106127360L, true, "1A2B-3C4D", "/storage/1A2B-3C4D");
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(Arrays.asList(dev));
        String[] lines = out.split("\n");
        Assert.assertEquals(4, lines.length);
        Assert.assertEquals("sda       15G     mass-storage  /storage/1A2B-3C4D", lines[2]);
        // Notice always appended (RF-2), exactly one line after the header block.
        Assert.assertTrue(lines[3].contains("(informational)"));
    }

    @Test
    public void testFormatLsblk_unmountedDevice() {
        UsbBlockDeviceFormatLibrary.UsbBlockDevice dev =
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sdb", 536870912L, true, null, null);
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(Arrays.asList(dev));
        String[] lines = out.split("\n");
        Assert.assertEquals("sdb       512M    mass-storage  -", lines[2]);
    }

    @Test
    public void testFormatLsblk_nonMassStorageDevice() {
        UsbBlockDeviceFormatLibrary.UsbBlockDevice dev =
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sdc", 1000L, false, null, null);
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(Arrays.asList(dev));
        String[] lines = out.split("\n");
        Assert.assertEquals("sdc       1000B   usb           -", lines[2]);
    }

    @Test
    public void testFormatLsblk_skipsNullEntries() {
        UsbBlockDeviceFormatLibrary.UsbBlockDevice dev =
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sda", 4096L, true, null, null);
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> devices =
            Arrays.asList(dev, null, dev);
        String out = UsbBlockDeviceFormatLibrary.formatLsblk(devices);
        Assert.assertEquals(5, out.split("\n").length);
    }

    // ------------------------------------------------------------------
    // parseVolumeId — /storage/XXXX-YYYY (RF-4)
    // ------------------------------------------------------------------

    @Test
    public void testParseVolumeId_valid() {
        Assert.assertEquals("1A2B-3C4D", UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/1A2B-3C4D"));
        Assert.assertEquals("1A2B-3C4D", UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/1A2B-3C4D/DCIM/photo.jpg"));
        Assert.assertEquals("1A2B-3C4D", UsbBlockDeviceFormatLibrary.parseVolumeId("1A2B-3C4D"));
        Assert.assertEquals("0DC4-F9AB", UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/0DC4-F9AB"));
    }

    @Test
    public void testParseVolumeId_invalid() {
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId(null));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId(""));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId("/storage"));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/emulated"));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/ABCD-234")); // 3 chars tail
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/AB-3C4D")); // 2 chars head
        Assert.assertNull(UsbBlockDeviceFormatLibrary.parseVolumeId("not-a-volume-id"));
    }

    @Test
    public void testParseVolumeId_normalizesCasing() {
        Assert.assertEquals("1A2B-3C4D", UsbBlockDeviceFormatLibrary.parseVolumeId("/storage/1a2b-3c4d"));
    }

    // ------------------------------------------------------------------
    // JSON round-trip de block devices (RF-1, RF-6)
    // ------------------------------------------------------------------

    @Test
    public void testJsonRoundTrip_devices() {
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> devices = Arrays.asList(
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sda", 16106127360L, true, "1A2B-3C4D", "/storage/1A2B-3C4D"),
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sdb", 0L, false, null, null)
        );
        String json = UsbBlockDeviceFormatLibrary.toJson(devices);
        Assert.assertTrue(json.contains("\"name\":\"sda\""));
        Assert.assertTrue(json.contains("\"massStorage\":true"));
        Assert.assertTrue(json.contains("\"volumeId\":\"1A2B-3C4D\""));
        Assert.assertFalse(json.contains("\"mountpoint\":\"null\""));

        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> parsed = UsbBlockDeviceFormatLibrary.fromJson(json);
        Assert.assertEquals(2, parsed.size());
        UsbBlockDeviceFormatLibrary.UsbBlockDevice a = parsed.get(0);
        Assert.assertEquals("sda", a.name);
        Assert.assertEquals(16106127360L, a.capacityBytes);
        Assert.assertTrue(a.massStorage);
        Assert.assertEquals("1A2B-3C4D", a.volumeId);
        Assert.assertEquals("/storage/1A2B-3C4D", a.mountpoint);
        UsbBlockDeviceFormatLibrary.UsbBlockDevice b = parsed.get(1);
        Assert.assertEquals("sdb", b.name);
        Assert.assertFalse(b.massStorage);
        Assert.assertNull(b.volumeId);
        Assert.assertNull(b.mountpoint);
    }

    @Test
    public void testJsonRoundTrip_emptyAndInvalid() {
        Assert.assertEquals("[]", UsbBlockDeviceFormatLibrary.toJson(null));
        Assert.assertEquals("[]", UsbBlockDeviceFormatLibrary.toJson(new ArrayList<>()));
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.fromJson(null).isEmpty());
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.fromJson("not json").isEmpty());
        Assert.assertTrue(UsbBlockDeviceFormatLibrary.fromJson("[{\"massStorage\":true}]").isEmpty()); // missing name
    }

    @Test
    public void testJson_escapesSpecialChars() {
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> devices = Arrays.asList(
            new UsbBlockDeviceFormatLibrary.UsbBlockDevice("sd\"a", 1L, true, "FA\"KE-BAD1", null)
        );
        String json = UsbBlockDeviceFormatLibrary.toJson(devices);
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> parsed = UsbBlockDeviceFormatLibrary.fromJson(json);
        Assert.assertEquals(1, parsed.size());
        Assert.assertEquals("sd\"a", parsed.get(0).name);
        Assert.assertEquals("FA\"KE-BAD1", parsed.get(0).volumeId);
    }

    // ------------------------------------------------------------------
    // encode/decodePathForBridge (RF-4)
    // ------------------------------------------------------------------

    @Test
    public void testEncodePathForBridge_safeCased() {
        // Path-safe characters are preserved.
        Assert.assertEquals("/storage/1A2B-3C4D/DCIM/photo.jpg",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("/storage/1A2B-3C4D/DCIM/photo.jpg"));
        Assert.assertEquals("/dir/file.txt",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("/dir/file.txt"));
        Assert.assertEquals("a_b-c.d~e:f",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("a_b-c.d~e:f"));
    }

    @Test
    public void testEncodePathForBridge_spacesAndUtf8() {
        Assert.assertEquals("/storage/1A2B-3C4D/my%20photos",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("/storage/1A2B-3C4D/my photos"));
        Assert.assertEquals("%C3%B1.txt",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("ñ.txt"));
        Assert.assertEquals("%E2%9C%93",
            UsbBlockDeviceFormatLibrary.encodePathForBridge("✓"));
        Assert.assertEquals("a%25b", UsbBlockDeviceFormatLibrary.encodePathForBridge("a%b"));
    }

    @Test
    public void testEncodePathForBridge_nullReturnsNull() {
        Assert.assertNull(UsbBlockDeviceFormatLibrary.encodePathForBridge(null));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.decodePathForBridge(null));
    }

    @Test
    public void testDecodePathForBridge_valid() {
        Assert.assertEquals("/storage/1A2B-3C4D/my photos",
            UsbBlockDeviceFormatLibrary.decodePathForBridge("/storage/1A2B-3C4D/my%20photos"));
        Assert.assertEquals("ñ.txt", UsbBlockDeviceFormatLibrary.decodePathForBridge("%C3%B1.txt"));
        Assert.assertEquals("/simple/path", UsbBlockDeviceFormatLibrary.decodePathForBridge("/simple/path"));
    }

    @Test
    public void testDecodePathForBridge_invalidReturnsNull() {
        Assert.assertNull(UsbBlockDeviceFormatLibrary.decodePathForBridge("%"));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.decodePathForBridge("%2"));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.decodePathForBridge("%ZZ"));
        Assert.assertNull(UsbBlockDeviceFormatLibrary.decodePathForBridge("trailing%2"));
    }

    @Test
    public void testBridgePathRoundTrip() {
        String[] paths = {
            "/storage/1A2B-3C4D/my photos/na ñ.jpg",
            "/plain/path",
            "café ☕/file#1?.txt"
        };
        for (String p : paths) {
            Assert.assertEquals(p, UsbBlockDeviceFormatLibrary.decodePathForBridge(
                UsbBlockDeviceFormatLibrary.encodePathForBridge(p)));
        }
    }
}
