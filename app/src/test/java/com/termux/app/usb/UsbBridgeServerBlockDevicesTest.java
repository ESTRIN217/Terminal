package com.termux.app.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.usb.UsbBlockDeviceFormatLibrary;
import com.termux.shared.termux.usb.UsbDeviceFormatLibrary;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the spec-021 T6 extension of the USB snapshot
 * ({@code /tmp/usb_devices.json}): a "blockDevices" additive section on each
 * mass-storage (class 0x08) device entry, enlisting hints of the volumes
 * mounted by the Android framework (under {@code /storage/XXXX-YYYY}).
 *
 * <p>The device/section classification comes from the pure library
 * {@link UsbBlockDeviceFormatLibrary}; the server only enlists it. These tests
 * run on the JVM with a mocked enlistment (no Android runtime needed).</p>
 */
public class UsbBridgeServerBlockDevicesTest {

    @NonNull
    private static UsbDeviceFormatLibrary.UsbDeviceInfo msDevice(int deviceClass) {
        return new UsbDeviceFormatLibrary.UsbDeviceInfo(
            "/dev/bus/usb/001/002", 1, 2,
            0x1A3B, 0x2C4D, "Acme", "Flash Drive",
            deviceClass, 0x06, 0x50);
    }

    @NonNull
    private static UsbDeviceFormatLibrary.UsbDeviceInfo hubDevice() {
        return new UsbDeviceFormatLibrary.UsbDeviceInfo(
            "/dev/bus/usb/001/003", 1, 3,
            0x1D6B, 0x0002, "Linux Foundation", "hub",
            0x09, 0x00, 0x01);
    }

    /** Extracts the last {@code blockDevices} array of the given JSON object scan. */
    @Nullable
    private static String extractBlockDevices(@NonNull String json, int fromIndex) {
        int keyIndex = json.indexOf("\"blockDevices\":[", fromIndex);
        if (keyIndex == -1) return null;
        int arrayStart = keyIndex + "\"blockDevices\":".length();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '[') depth++;
            if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(arrayStart, i + 1);
            }
        }
        return null;
    }

    @Test
    public void testMassStorageDeviceGainsBlockDevicesSection() {
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.singletonList(msDevice(0x08)),
            null,
            Collections.singletonList("/storage/1A2B-3C4D"));

        String section = extractBlockDevices(json, 0);
        org.junit.Assert.assertNotNull("mass-storage entry must gain blockDevices", section);

        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> blocks =
            UsbBlockDeviceFormatLibrary.fromJson(section);
        assertEquals(1, blocks.size());
        UsbBlockDeviceFormatLibrary.UsbBlockDevice b = blocks.get(0);
        assertEquals("1A2B-3C4D", b.name);
        assertTrue(b.massStorage);
        assertEquals("1A2B-3C4D", b.volumeId);
        assertEquals("/storage/1A2B-3C4D", b.mountpoint);
    }

    @Test
    public void testMassStorageByInterfaceClassOnly() {
        // Device class 0x00 but one interface of class 0x08 (very common for
        // mass-storage devices that report per-interface classes).
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.singletonList(msDevice(0x00)),
            Collections.singletonMap("/dev/bus/usb/001/002", new int[]{0x08}),
            Collections.singletonList("/storage/0EAD-F00D"));

        List<UsbDeviceFormatLibrary.UsbDeviceInfo> devs = UsbDeviceFormatLibrary.fromJson(json);
        assertEquals(1, devs.size());
        String section = extractBlockDevices(json, 0);
        org.junit.Assert.assertNotNull(section);
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> blocks =
            UsbBlockDeviceFormatLibrary.fromJson(section);
        assertEquals(1, blocks.size());
        assertEquals("0EAD-F00D", blocks.get(0).volumeId);
    }

    @Test
    public void testNonMassStorageDeviceHasNoBlockDevices() {
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.singletonList(hubDevice()),
            null,
            Collections.singletonList("/storage/1A2B-3C4D"));

        assertNull(extractBlockDevices(json, 0));
        // The 020 array shape is untouched for non mass-storage devices.
        List<UsbDeviceFormatLibrary.UsbDeviceInfo> devs = UsbDeviceFormatLibrary.fromJson(json);
        assertEquals(1, devs.size());
        assertEquals(0x09, devs.get(0).deviceClass);
    }

    @Test
    public void testMassStorageWithoutMountedVolumesUsesSdaFallback() {
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            java.util.Arrays.asList(hubDevice(), msDevice(0x08)),
            null,
            Collections.emptyList());

        String section = extractBlockDevices(json, 0);
        org.junit.Assert.assertNotNull(
            "mass-storage entry without mounted volumes must still report the flag", section);
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> blocks =
            UsbBlockDeviceFormatLibrary.fromJson(section);
        assertEquals(1, blocks.size());
        UsbBlockDeviceFormatLibrary.UsbBlockDevice b = blocks.get(0);
        assertEquals("sda", b.name);
        assertTrue(b.massStorage);
        assertNull(b.volumeId);
        assertNull(b.mountpoint);
    }

    @Test
    public void testExistingGlyph020FieldsArePreservedAfterMerge() {
        UsbDeviceFormatLibrary.UsbDeviceInfo original = msDevice(0x08);
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.singletonList(original),
            null,
            Collections.singletonList("/storage/1A2B-3C4D"));

        List<UsbDeviceFormatLibrary.UsbDeviceInfo> parsed = UsbDeviceFormatLibrary.fromJson(json);
        assertEquals(1, parsed.size());
        UsbDeviceFormatLibrary.UsbDeviceInfo d = parsed.get(0);
        assertEquals(original.path, d.path);
        assertEquals(original.busNumber, d.busNumber);
        assertEquals(original.deviceNumber, d.deviceNumber);
        assertEquals(original.vendorId, d.vendorId);
        assertEquals(original.productId, d.productId);
        assertEquals(original.manufacturer, d.manufacturer);
        assertEquals(original.productName, d.productName);
        assertEquals(original.deviceClass, d.deviceClass);
        assertEquals(original.deviceSubclass, d.deviceSubclass);
        assertEquals(original.deviceProtocol, d.deviceProtocol);

        // VID/PID stay available to "block device volumes" through the enclosing
        // 020 entry (RF-6: VID/PID + interfaz 0x08 + nombre de volumen).
        assertTrue(json.contains("\"vendorId\":6715"));
        assertTrue(json.contains("\"productId\":11341"));
    }

    @Test
    public void testInvalidVolumeDirectoryNamesAreSkipped() {
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.singletonList(msDevice(0x08)),
            null,
            java.util.Arrays.asList("/storage/not-a-volume", "/storage/1A2B-3C4D"));

        String section = extractBlockDevices(json, 0);
        org.junit.Assert.assertNotNull(section);
        List<UsbBlockDeviceFormatLibrary.UsbBlockDevice> blocks =
            UsbBlockDeviceFormatLibrary.fromJson(section);
        assertEquals(1, blocks.size());
        assertEquals("1A2B-3C4D", blocks.get(0).name);
        assertFalse(json.contains("not-a-volume"));
    }

    @Test
    public void testEmptyDeviceListStaysAPlainArray() {
        String json = UsbBridgeServer.mergeBlockDevicesIntoJson(
            Collections.emptyList(), null, Collections.singletonList("/storage/1A2B-3C4D"));
        assertEquals("[]", json);
    }

    @Test
    public void testListMountedVolumeIdsFiltersInvalidAndReturnsPaths() throws Exception {
        File root = Files.createTempDirectory("storage-root").toFile();
        try {
            new File(root, "1A2B-3C4D").mkdirs();
            new File(root, "emulated").mkdirs();
            new File(root, "self").mkdirs();
            new File(root, "afile.txt").createNewFile();

            List<String> ids = UsbBridgeServer.listMountedVolumeIds(root.getAbsolutePath());
            assertEquals(
                Collections.singletonList(root.getAbsolutePath() + "/1A2B-3C4D"), ids);
        } finally {
            for (File f : root.listFiles()) {
                org.junit.Assert.assertTrue(f.delete());
            }
            org.junit.Assert.assertTrue(root.delete());
        }
    }

    @Test
    public void testListMountedVolumeIdsMissingRoot() {
        List<String> ids = UsbBridgeServer.listMountedVolumeIds(
            "/nonexistent/storage/root/for/tests");
        assertTrue(ids.isEmpty());
    }

    @Test
    public void testWriteSnapshotFiles_isIdempotentAndEmitsBothFiles() throws Exception {
        // Spec 021 T8 enmienda RF-6: updateDeviceSnapshot se dispara just-in-time
        // en cada conexión al bridge (handleClient). La parte observable por
        // test JVM es que re-escribir el snapshot con las mismas entradas es
        // idempotente (dos conexiones seguidas no mutan el fichero); el disparo
        // real desde handleClient se verifica en dispositivo (contexto Android).
        File tmpDir = Files.createTempDirectory("usb-snapshot-t8").toFile();
        try {
            List<UsbDeviceFormatLibrary.UsbDeviceInfo> devices = Collections.singletonList(msDevice(0x08));
            List<String> volumes = Collections.singletonList("/storage/1A2B-3C4D");

            UsbBridgeServer.writeSnapshotFiles(devices, null, volumes, tmpDir);
            byte[] json1 = Files.readAllBytes(new File(tmpDir, "usb_devices.json").toPath());
            byte[] txt1 = Files.readAllBytes(new File(tmpDir, "usb_devices.txt").toPath());
            assertTrue(json1.length > 0);
            assertTrue(new String(json1, java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"blockDevices\""));

            UsbBridgeServer.writeSnapshotFiles(devices, null, volumes, tmpDir);
            byte[] json2 = Files.readAllBytes(new File(tmpDir, "usb_devices.json").toPath());
            byte[] txt2 = Files.readAllBytes(new File(tmpDir, "usb_devices.txt").toPath());
            org.junit.Assert.assertArrayEquals("snapshot rewrite must be idempotent", json1, json2);
            org.junit.Assert.assertArrayEquals(txt1, txt2);
        } finally {
            for (File f : tmpDir.listFiles()) {
                assertTrue(f.delete());
            }
            assertTrue(tmpDir.delete());
        }
    }

    @Test
    public void testWriteSnapshotFiles_releasesDetachedVolume() throws Exception {
        // El refresco JIT sirve para eso: una conexión tras DETACHED re-pinta
        // el snapshot sin el volumen, de forma idempotente.
        File tmpDir = Files.createTempDirectory("usb-snapshot-t8b").toFile();
        try {
            List<UsbDeviceFormatLibrary.UsbDeviceInfo> devices = Collections.singletonList(msDevice(0x08));
            UsbBridgeServer.writeSnapshotFiles(devices, null,
                Collections.singletonList("/storage/1A2B-3C4D"), tmpDir);
            byte[] withVolume = Files.readAllBytes(new File(tmpDir, "usb_devices.json").toPath());
            assertTrue(new String(withVolume, java.nio.charset.StandardCharsets.UTF_8)
                .contains("1A2B-3C4D"));

            UsbBridgeServer.writeSnapshotFiles(devices, null, Collections.emptyList(), tmpDir);
            byte[] afterDetach = Files.readAllBytes(new File(tmpDir, "usb_devices.json").toPath());
            String json = new String(afterDetach, java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(json.contains("1A2B-3C4D"));
            assertTrue(json.contains("sda"));
        } finally {
            for (File f : tmpDir.listFiles()) {
                assertTrue(f.delete());
            }
            assertTrue(tmpDir.delete());
        }
    }
}
