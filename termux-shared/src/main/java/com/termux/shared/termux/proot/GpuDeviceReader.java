package com.termux.shared.termux.proot;

import android.annotation.SuppressLint;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.reflection.ReflectionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Android glue of spec 011 (RF-1, RF-6): reads the real GPU identity sources of
 * the host device and returns them as the injectable {@link
 * GpuDetectionLibrary.GpuInputs} DTO consumed by the pure {@link
 * GpuDetectionLibrary}.
 *
 * <p><b>Glue/pure contrast:</b> everything touchable here is device state
 * (sysfs, device node, system properties, {@link Build.SOC_MODEL}), so it
 * cannot be exercised by the plain JUnit suite: there is no parse or
 * classification logic in this class. The only decision on the sysfs text —
 * its {@code trim}/whitespace-collapse sanitation of the {@code gpu_model} —
 * lives in the pure library and is already covered by {@code
 * GpuDetectionLibraryTest} ({@code ..._andSanitizedModel}, {@code
 * ..._NewlinesCollapsed}); this reader deliberately hands the raw bytes over
 * untouched.</p>
 *
 * <p><b>Failure policy (RF-6):</b> no read failure may kill the process nor be
 * swallowed silently. Every risky read is wrapped in a targeted {@code catch}
 * that logs a {@link Error} (readable via logcat) and degrades to the safe
 * default ({@code null}/{@code false}); the pure classifier is responsible for
 * turning the degraded DTO into {@code DESCONOCIDA}-style honest results.</p>
 */
public final class GpuDeviceReader {

    private static final String LOG_TAG = "GpuDeviceReader";

    /** Host path of the Adreno KGSL sysfs model file (kernel truth, RF-1 (a)); absent on non-Adreno/kgsl devices. */
    public static final String KGSL_GPU_MODEL_SYSFS_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_model";
    /** Host KGSL device node whose presence independently certifies KGSL (RF-1 (a)). */
    public static final String KGSL_DEVICE_NODE_PATH = "/dev/kgsl-3d0";
    /** Hidden android class exposing single-key system property reads (reflection, like the rest of the repo). */
    public static final String ANDROID_OS_SYSTEMPROPERTIES_CLASS = "android.os.SystemProperties";
    /** EGL hardware sysprop (OEM appearance, RF-1 (b)). */
    public static final String SYSPROP_EGL_HARDWARE = "ro.hardware.egl";
    /** Board platform sysprop (OEM appearance, RF-1 (b)). */
    public static final String SYSPROP_BOARD_PLATFORM = "ro.board.platform";
    /** {@link Build.SOC_MODEL} is populated only from API 31; minSdk is 24, so a guard is mandatory. */
    private static final int SOC_MODEL_MIN_API = Build.VERSION_CODES.S;
    /** Upper bound for a gpu_model read (sysfs fields stay single-line). */
    private static final int GPU_MODEL_MAX_BYTES = 4096;

    private GpuDeviceReader() {
        // Glue utility class; stateless (per project architecture rules).
    }

    /**
     * Read every GPU source in one pass and return the injectable DTO. Each
     * source degrades independently to {@code null}/{@code false} with a logged
     * {@link Error} on failure; absence (e.g. no KGSL sysfs on a Mali device)
     * is a normal outcome, not an error, and is only traced at debug level.
     *
     * @return Returns the {@link GpuDetectionLibrary.GpuInputs} DTO, never
     * {@code null}; every field degrades safely when its source is unreadable.
     */
    @NonNull
    public static GpuDetectionLibrary.GpuInputs read() {
        String gpuModel = readSysfsGpuModel();
        boolean kgslDevNode = isKgslDevNodePresent();
        String hwEgl = getSystemProperty(SYSPROP_EGL_HARDWARE);
        String boardPlatform = getSystemProperty(SYSPROP_BOARD_PLATFORM);
        String socModel = getSocModel();
        return new GpuDetectionLibrary.GpuInputs(
            gpuModel, kgslDevNode, hwEgl, boardPlatform, socModel);
    }

    /**
     * Read the raw {@code /sys/class/kgsl/kgsl-3d0/gpu_model} content. The text
     * is returned untouched: the trim/collapse sanitation is a pure-library
     * concern (see class javadoc).
     *
     * @return Returns the raw file content, or {@code null} when the file does
     * not exist (normal on non-KGSL devices) or its read failed (logged, not
     * thrown, RF-6).
     */
    @Nullable
    public static String readSysfsGpuModel() {
        File file = new File(KGSL_GPU_MODEL_SYSFS_PATH);
        if (!file.isFile()) {
            // Kernel-way absence of kgsl-3d0 is the expected state on all devices
            // without Adreno KGSL; traced, not treated as a failure.
            Logger.logDebug(LOG_TAG, "No kgsl gpu_model sysfs at " + KGSL_GPU_MODEL_SYSFS_PATH);
            return null;
        }
        byte[] bytes = new byte[GPU_MODEL_MAX_BYTES];
        int read;
        try (FileInputStream in = new FileInputStream(file)) {
            read = in.read(bytes, 0, bytes.length);
        } catch (IOException | SecurityException e) {
            Error error = new Error("Failed to read gpu_model from " + KGSL_GPU_MODEL_SYSFS_PATH, e);
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return null;
        }
        if (read <= 0) {
            Logger.logWarnExtended(LOG_TAG, "kgsl gpu_model sysfs was empty at " + KGSL_GPU_MODEL_SYSFS_PATH);
            return null;
        }
        return new String(bytes, 0, read, StandardCharsets.UTF_8);
    }

    /**
     * Check the existence of the KGSL device node {@code /dev/kgsl-3d0}.
     *
     * @return Returns {@code true} when the node exists, {@code false} when it
     * does not exist or the check itself failed (logged, not thrown, RF-6).
     */
    public static boolean isKgslDevNodePresent() {
        File node = new File(KGSL_DEVICE_NODE_PATH);
        try {
            return node.exists();
        } catch (SecurityException e) {
            Error error = new Error("Failed to check the KGSL device node at " + KGSL_DEVICE_NODE_PATH, e);
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return false;
        }
    }

    /**
     * Read a single system property via {@code SystemProperties.get}
     * reflection (same technique as the rest of the repo, e.g.
     * {@code SELinuxUtils.getContext}); the plain-JVM {@code System.getProperty}
     * and the {@code getprop} process dump of {@code AndroidUtils.getSystemProperties()}
     * are not suitable for one keyed read.
     *
     * @param property System property key (e.g. {@code ro.hardware.egl}).
     * @return Returns the property value, or {@code null} when unset, empty or
     * unreadable (logged, not thrown, RF-6).
     */
    @Nullable
    public static String getSystemProperty(@NonNull String property) {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions();
        try {
            @SuppressLint("PrivateApi")
            Class<?> clazz = Class.forName(ANDROID_OS_SYSTEMPROPERTIES_CLASS);
            Method method = ReflectionUtils.getDeclaredMethod(clazz, "get", String.class);
            if (method == null) {
                Error error = new Error("Failed to get get() method of "
                    + ANDROID_OS_SYSTEMPROPERTIES_CLASS + " while reading \"" + property + "\"");
                Logger.logErrorExtended(LOG_TAG, error.toString());
                return null;
            }
            Object value = ReflectionUtils.invokeMethod(method, null, (Object) property).value;
            if (value instanceof String) {
                String stringValue = (String) value;
                return stringValue.isEmpty() ? null : stringValue;
            }
            return null;
        } catch (Exception e) {
            Error error = new Error("Failed to get the \"" + property + "\" system property", e);
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return null;
        }
    }

    /**
     * Read {@link Build.SOC_MODEL}, guarded by the API level filter required by
     * the plan RF-1: the field only exists (and is populated) from API 31, while
     * the project minSdk is 24; below the guard {@code null} is returned so a
     * stale firmware value can never leak in. Values that Android defines as
     * unknown are treated as absent data.
     *
     * @return Returns the SOC model string, or {@code null} on API &lt; 31 or
     * when the platform reports no real value.
     */
    @Nullable
    public static String getSocModel() {
        // Code-reviewed guard (RF-1): Build.SOC_MODEL exists from API 31 only.
        if (Build.VERSION.SDK_INT < SOC_MODEL_MIN_API)
            return null;
        String value = Build.SOC_MODEL;
        if (value == null || value.isEmpty() || Build.UNKNOWN.equals(value))
            return null;
        return value;
    }
}
