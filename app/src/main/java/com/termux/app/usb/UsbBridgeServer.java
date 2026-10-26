package com.termux.app.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.usb.UsbDeviceFormatLibrary;
import com.termux.shared.termux.usb.UsbDeviceFormatLibrary.UsbDeviceInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android USB host bridge server (spec 020).
 *
 * <p>Listens on the abstract namespace socket {@code @terminal_usb} (and manages
 * {@code /tmp/usb_devices.json} and {@code /tmp/usb_devices.txt}). Responds to
 * guest commands {@code LIST}, {@code REQUEST <path>} and {@code OPEN <path>}.
 * When a device is opened, its file descriptor is passed to the guest client
 * via {@code SCM_RIGHTS}.</p>
 */
public final class UsbBridgeServer {

    private static final String LOG_TAG = "UsbBridgeServer";
    public static final String SOCKET_NAME = "terminal_usb";
    public static final String ACTION_USB_PERMISSION = "com.estrin217.terminal.USB_PERMISSION";

    private static volatile LocalServerSocket mServerSocket;
    private static volatile Thread mServerThread;
    private static volatile BroadcastReceiver mUsbReceiver;
    private static volatile boolean mIsRunning = false;
    private static ExecutorService mClientExecutor;

    private UsbBridgeServer() {
        // Utility class.
    }

    /**
     * Start or stop the USB bridge server depending on the number of active sessions.
     */
    public static synchronized Error applySessionCount(@Nullable Context context, int activeProotSessions) {
        if (activeProotSessions > 0) {
            if (!mIsRunning && context != null) {
                return start(context.getApplicationContext());
            }
        } else {
            if (mIsRunning) {
                stop(context != null ? context.getApplicationContext() : null);
            }
        }
        return null;
    }

    /**
     * Whether the bridge server is currently running.
     */
    public static synchronized boolean isAlive() {
        return mIsRunning && mServerSocket != null;
    }

    /**
     * Start the USB bridge server and snapshot writer.
     */
    public static synchronized Error start(@NonNull Context context) {
        if (mIsRunning) return null;
        Logger.logInfo(LOG_TAG, "Starting USB bridge server...");

        try {
            mServerSocket = new LocalServerSocket(SOCKET_NAME);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to bind LocalServerSocket @" + SOCKET_NAME, e);
            return new Error("Failed to bind LocalServerSocket @" + SOCKET_NAME, e);
        }

        mIsRunning = true;
        mClientExecutor = Executors.newCachedThreadPool();

        mServerThread = new Thread(() -> acceptLoop(context), "usb-bridge-server");
        mServerThread.start();

        registerUsbReceiver(context);
        updateDeviceSnapshot(context);

        Logger.logInfo(LOG_TAG, "USB bridge server started successfully.");
        return null;
    }

    /**
     * Stop the USB bridge server.
     */
    public static synchronized void stop(@Nullable Context context) {
        if (!mIsRunning) return;
        Logger.logInfo(LOG_TAG, "Stopping USB bridge server...");
        mIsRunning = false;

        if (context != null) {
            unregisterUsbReceiver(context);
        }

        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
            }
            mServerSocket = null;
        }

        if (mServerThread != null) {
            mServerThread.interrupt();
            mServerThread = null;
        }

        if (mClientExecutor != null) {
            mClientExecutor.shutdownNow();
            mClientExecutor = null;
        }

        // Clean up snapshots
        new File(TermuxConstants.USB_DEVICES_SNAPSHOT_FILE_PATH).delete();
        new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/tmp/usb_devices.txt").delete();

        Logger.logInfo(LOG_TAG, "USB bridge server stopped.");
    }

    private static void registerUsbReceiver(@NonNull Context context) {
        if (mUsbReceiver != null) return;
        mUsbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
                    UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    Logger.logDebug(LOG_TAG, "USB device attached/detached: " + action);
                    updateDeviceSnapshot(ctx);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(mUsbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(mUsbReceiver, filter);
        }
    }

    private static void unregisterUsbReceiver(@NonNull Context context) {
        if (mUsbReceiver != null) {
            try {
                context.unregisterReceiver(mUsbReceiver);
            } catch (Exception ignored) {
            }
            mUsbReceiver = null;
        }
    }

    /**
     * Refresh the snapshot files (/tmp/usb_devices.json and /tmp/usb_devices.txt).
     */
    public static synchronized void updateDeviceSnapshot(@NonNull Context context) {
        List<UsbDeviceInfo> devices = enumerateDevices(context);

        String json = UsbDeviceFormatLibrary.toJson(devices);
        StringBuilder txt = new StringBuilder();
        txt.append(UsbDeviceFormatLibrary.formatRootHubLsusbLine(1)).append("\n");
        for (UsbDeviceInfo d : devices) {
            txt.append(UsbDeviceFormatLibrary.formatLsusbLine(d)).append("\n");
        }

        File tmpDir = new File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }

        writeStringToFile(new File(TermuxConstants.USB_DEVICES_SNAPSHOT_FILE_PATH), json);
        writeStringToFile(new File(tmpDir, "usb_devices.txt"), txt.toString());
    }

    private static void writeStringToFile(File file, String content) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.logDebug(LOG_TAG, "Failed to write snapshot to " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    @NonNull
    public static List<UsbDeviceInfo> enumerateDevices(@NonNull Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return Collections.emptyList();

        Map<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) return Collections.emptyList();

        List<UsbDeviceInfo> result = new ArrayList<>();
        int defaultDevNum = 2;
        for (UsbDevice dev : deviceList.values()) {
            if (dev == null) continue;
            String name = dev.getDeviceName();
            int[] busDev = UsbDeviceFormatLibrary.parseDevicePath(name);
            int bus = busDev != null ? busDev[0] : 1;
            int devNum = busDev != null ? busDev[1] : defaultDevNum++;

            String canonicalPath = UsbDeviceFormatLibrary.buildDevicePath(bus, devNum);

            String manufacturer = null;
            String product = null;
            try {
                manufacturer = dev.getManufacturerName();
            } catch (SecurityException ignored) {
            }
            try {
                product = dev.getProductName();
            } catch (SecurityException ignored) {
            }

            result.add(new UsbDeviceInfo(
                canonicalPath,
                bus,
                devNum,
                dev.getVendorId(),
                dev.getProductId(),
                manufacturer,
                product,
                dev.getDeviceClass(),
                dev.getDeviceSubclass(),
                dev.getDeviceProtocol()
            ));
        }
        return result;
    }

    @Nullable
    private static UsbDevice findDeviceByPath(@NonNull Context context, @NonNull String path) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return null;

        Map<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null) return null;

        // Try direct name match
        for (UsbDevice dev : deviceList.values()) {
            if (dev != null && dev.getDeviceName().equalsIgnoreCase(path.trim())) {
                return dev;
            }
        }

        // Try parsed bus and dev matching
        int[] target = UsbDeviceFormatLibrary.parseDevicePath(path);
        if (target != null) {
            int defaultDevNum = 2;
            for (UsbDevice dev : deviceList.values()) {
                if (dev == null) continue;
                int[] busDev = UsbDeviceFormatLibrary.parseDevicePath(dev.getDeviceName());
                int b = busDev != null ? busDev[0] : 1;
                int d = busDev != null ? busDev[1] : defaultDevNum++;
                if (b == target[0] && d == target[1]) {
                    return dev;
                }
            }
        }
        return null;
    }

    private static void acceptLoop(@NonNull Context context) {
        while (mIsRunning) {
            try {
                LocalSocket client = mServerSocket.accept();
                if (client != null && mClientExecutor != null && !mClientExecutor.isShutdown()) {
                    mClientExecutor.execute(() -> handleClient(context, client));
                }
            } catch (IOException e) {
                if (!mIsRunning) break;
                Logger.logStackTraceWithMessage(LOG_TAG, "Error in acceptLoop", e);
            }
        }
    }

    private static void handleClient(@NonNull Context context, @NonNull LocalSocket client) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {

            String line = reader.readLine();
            if (line == null) {
                client.close();
                return;
            }

            line = line.trim();
            if ("LIST".equals(line)) {
                List<UsbDeviceInfo> devices = enumerateDevices(context);
                String json = UsbDeviceFormatLibrary.toJson(devices);
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else if (line.startsWith("REQUEST ")) {
                String devPath = line.substring(8).trim();
                handleRequestPermission(context, devPath, out);
            } else if (line.startsWith("OPEN ")) {
                String devPath = line.substring(5).trim();
                handleOpenDevice(context, client, devPath, out);
            } else {
                out.write("ERR: Unknown command\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            Logger.logDebug(LOG_TAG, "Client connection ended: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleRequestPermission(@NonNull Context context, @NonNull String devPath, @NonNull OutputStream out) throws IOException {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            out.write("ERR: UsbManager unavailable\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        UsbDevice device = findDeviceByPath(context, devPath);
        if (device == null) {
            out.write(("ERR: Device not found: " + devPath + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        if (usbManager.hasPermission(device)) {
            out.write("OK: GRANTED\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        boolean granted = requestPermissionBlocking(context, usbManager, device);
        if (granted) {
            out.write("OK: GRANTED\n".getBytes(StandardCharsets.UTF_8));
        } else {
            out.write("ERR: DENIED\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    private static void handleOpenDevice(@NonNull Context context, @NonNull LocalSocket client, @NonNull String devPath, @NonNull OutputStream out) throws IOException {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            out.write("ERR: UsbManager unavailable\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        UsbDevice device = findDeviceByPath(context, devPath);
        if (device == null) {
            out.write(("ERR: Device not found: " + devPath + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        if (!usbManager.hasPermission(device)) {
            boolean granted = requestPermissionBlocking(context, usbManager, device);
            if (!granted) {
                out.write("ERR: DENIED\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            out.write("ERR: Failed to open device connection\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        int fd = connection.getFileDescriptor();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.fromFd(fd);
            FileDescriptor javaFd = pfd.getFileDescriptor();

            client.setFileDescriptorsForSend(new FileDescriptor[]{javaFd});
            out.write("OK: FD\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Keep the connection open until client closes its socket
            byte[] buf = new byte[32];
            try {
                while (client.getInputStream().read(buf) != -1) {
                    // Wait for EOF
                }
            } catch (IOException ignored) {
            }
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
            connection.close();
        }
    }

    private static boolean requestPermissionBlocking(@NonNull Context context, @NonNull UsbManager usbManager, @NonNull UsbDevice device) {
        final AtomicBoolean granted = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (d != null && d.getDeviceName().equals(device.getDeviceName())) {
                        granted.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                        latch.countDown();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pi);

            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Logger.logDebug(LOG_TAG, "Permission request interrupted");
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
        return granted.get();
    }
}
