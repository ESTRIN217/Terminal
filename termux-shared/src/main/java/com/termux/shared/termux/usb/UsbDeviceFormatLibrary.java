package com.termux.shared.termux.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure functions for USB device enumeration, formatting, and JSON serialization (spec 020).
 *
 * <p>All methods are stateless, pure, and have zero dependencies on the Android SDK
 * or Android runtime stubs so they can run directly on any JVM via standard JUnit tests.</p>
 */
public final class UsbDeviceFormatLibrary {

    private static final String LOG_TAG = "UsbDeviceFormatLibrary";

    private static final Pattern DEVICE_PATH_PATTERN = Pattern.compile("^(?:/dev/bus/usb/)?([0-9]{1,3})/([0-9]{1,3})$");

    private UsbDeviceFormatLibrary() {
        // Static utility class.
    }

    /**
     * Data model representing a USB device's properties.
     */
    public static final class UsbDeviceInfo {
        @NonNull public final String path;
        public final int busNumber;
        public final int deviceNumber;
        public final int vendorId;
        public final int productId;
        @Nullable public final String manufacturer;
        @Nullable public final String productName;
        public final int deviceClass;
        public final int deviceSubclass;
        public final int deviceProtocol;

        public UsbDeviceInfo(@NonNull String path,
                             int busNumber,
                             int deviceNumber,
                             int vendorId,
                             int productId,
                             @Nullable String manufacturer,
                             @Nullable String productName,
                             int deviceClass,
                             int deviceSubclass,
                             int deviceProtocol) {
            this.path = path;
            this.busNumber = busNumber;
            this.deviceNumber = deviceNumber;
            this.vendorId = vendorId;
            this.productId = productId;
            this.manufacturer = manufacturer != null && !manufacturer.trim().isEmpty() ? manufacturer.trim() : null;
            this.productName = productName != null && !productName.trim().isEmpty() ? productName.trim() : null;
            this.deviceClass = deviceClass;
            this.deviceSubclass = deviceSubclass;
            this.deviceProtocol = deviceProtocol;
        }
    }

    /**
     * Format a USB device as a classic {@code lsusb} output line.
     *
     * <p>Example: {@code "Bus 001 Device 002: ID 1a86:7523 QinHeng Electronics CH340"}.</p>
     *
     * @param busNumber Bus number (1-999)
     * @param deviceNumber Device number (1-999)
     * @param vendorId 16-bit USB Vendor ID
     * @param productId 16-bit USB Product ID
     * @param manufacturer Optional manufacturer string
     * @param productName Optional product name string
     * @return Formatted line.
     */
    @NonNull
    public static String formatLsusbLine(int busNumber,
                                         int deviceNumber,
                                         int vendorId,
                                         int productId,
                                         @Nullable String manufacturer,
                                         @Nullable String productName) {
        StringBuilder desc = new StringBuilder();
        if (manufacturer != null && !manufacturer.trim().isEmpty()) {
            desc.append(manufacturer.trim());
        }
        if (productName != null && !productName.trim().isEmpty()) {
            if (desc.length() > 0) desc.append(' ');
            desc.append(productName.trim());
        }

        String idPart = String.format(Locale.ROOT, "Bus %03d Device %03d: ID %04x:%04x",
            busNumber, deviceNumber, vendorId & 0xFFFF, productId & 0xFFFF);

        if (desc.length() > 0) {
            return idPart + " " + desc.toString();
        } else {
            return idPart;
        }
    }

    /**
     * Format a device using {@link UsbDeviceInfo}.
     */
    @NonNull
    public static String formatLsusbLine(@NonNull UsbDeviceInfo info) {
        return formatLsusbLine(info.busNumber, info.deviceNumber, info.vendorId, info.productId,
            info.manufacturer, info.productName);
    }

    /**
     * Format a synthetic root hub line for standard lsusb compliance.
     */
    @NonNull
    public static String formatRootHubLsusbLine(int busNumber) {
        return String.format(Locale.ROOT, "Bus %03d Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub", busNumber);
    }

    /**
     * Parse device path into {@code [busNumber, deviceNumber]}.
     *
     * @param path Path like {@code "/dev/bus/usb/001/002"} or {@code "001/002"}.
     * @return Array of two ints {@code [bus, dev]} or {@code null} if path is invalid.
     */
    @Nullable
    public static int[] parseDevicePath(@Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;
        Matcher m = DEVICE_PATH_PATTERN.matcher(path.trim());
        if (!m.matches()) return null;
        try {
            int bus = Integer.parseInt(m.group(1));
            int dev = Integer.parseInt(m.group(2));
            return new int[]{bus, dev};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Construct canonical device path {@code /dev/bus/usb/BBB/DDD}.
     */
    @NonNull
    public static String buildDevicePath(int busNumber, int deviceNumber) {
        return String.format(Locale.ROOT, "/dev/bus/usb/%03d/%03d", busNumber, deviceNumber);
    }

    /**
     * Serialize a list of {@link UsbDeviceInfo} into a JSON string using pure Java.
     */
    @NonNull
    public static String toJson(@Nullable List<UsbDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (UsbDeviceInfo d : devices) {
            if (d == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"path\":").append(escapeJsonString(d.path)).append(",");
            sb.append("\"busNumber\":").append(d.busNumber).append(",");
            sb.append("\"deviceNumber\":").append(d.deviceNumber).append(",");
            sb.append("\"vendorId\":").append(d.vendorId).append(",");
            sb.append("\"productId\":").append(d.productId).append(",");
            if (d.manufacturer != null) {
                sb.append("\"manufacturer\":").append(escapeJsonString(d.manufacturer)).append(",");
            }
            if (d.productName != null) {
                sb.append("\"productName\":").append(escapeJsonString(d.productName)).append(",");
            }
            sb.append("\"deviceClass\":").append(d.deviceClass).append(",");
            sb.append("\"deviceSubclass\":").append(d.deviceSubclass).append(",");
            sb.append("\"deviceProtocol\":").append(d.deviceProtocol);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize a JSON string into a list of {@link UsbDeviceInfo} using pure Java.
     */
    @NonNull
    public static List<UsbDeviceInfo> fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return Collections.emptyList();

        List<UsbDeviceInfo> result = new ArrayList<>();
        int i = 0;
        int len = trimmed.length();
        while (i < len) {
            int openBrace = trimmed.indexOf('{', i);
            if (openBrace == -1) break;
            int closeBrace = findMatchingBrace(trimmed, openBrace);
            if (closeBrace == -1) break;

            String objContent = trimmed.substring(openBrace + 1, closeBrace);
            UsbDeviceInfo info = parseObjectContent(objContent);
            if (info != null) {
                result.add(info);
            }
            i = closeBrace + 1;
        }
        return result;
    }

    private static int findMatchingBrace(String s, int openIndex) {
        boolean inQuote = false;
        boolean escape = false;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && c == '}') {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static UsbDeviceInfo parseObjectContent(String content) {
        Map<String, String> fields = new HashMap<>();
        int i = 0;
        int len = content.length();
        while (i < len) {
            // Find key
            int quoteStart = content.indexOf('"', i);
            if (quoteStart == -1) break;
            int quoteEnd = findClosingQuote(content, quoteStart);
            if (quoteEnd == -1) break;
            String key = unescapeJsonString(content.substring(quoteStart + 1, quoteEnd));

            int colon = content.indexOf(':', quoteEnd + 1);
            if (colon == -1) break;

            // Find value
            int valStart = colon + 1;
            while (valStart < len && Character.isWhitespace(content.charAt(valStart))) {
                valStart++;
            }
            if (valStart >= len) break;

            String val;
            int nextPos;
            if (content.charAt(valStart) == '"') {
                int valQuoteEnd = findClosingQuote(content, valStart);
                if (valQuoteEnd == -1) break;
                val = unescapeJsonString(content.substring(valStart + 1, valQuoteEnd));
                nextPos = valQuoteEnd + 1;
            } else {
                int comma = content.indexOf(',', valStart);
                if (comma == -1) {
                    val = content.substring(valStart).trim();
                    nextPos = len;
                } else {
                    val = content.substring(valStart, comma).trim();
                    nextPos = comma + 1;
                }
            }
            fields.put(key, val);
            i = nextPos;
        }

        String path = fields.get("path");
        if (path == null) return null;

        int bus = parseInt(fields.get("busNumber"), 1);
        int dev = parseInt(fields.get("deviceNumber"), 1);
        int vid = parseInt(fields.get("vendorId"), 0);
        int pid = parseInt(fields.get("productId"), 0);
        String man = fields.get("manufacturer");
        String prod = fields.get("productName");
        int dClass = parseInt(fields.get("deviceClass"), 0);
        int dSubclass = parseInt(fields.get("deviceSubclass"), 0);
        int dProtocol = parseInt(fields.get("deviceProtocol"), 0);

        return new UsbDeviceInfo(path, bus, dev, vid, pid, man, prod, dClass, dSubclass, dProtocol);
    }

    private static int findClosingQuote(String s, int openQuote) {
        boolean escape = false;
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int parseInt(@Nullable String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unescapeJsonString(String s) {
        if (s == null || s.indexOf('\\') == -1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                switch (c) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                int code = Integer.parseInt(s.substring(i + 1, i + 5), 16);
                                sb.append((char) code);
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                        break;
                    default:
                        sb.append(c);
                        break;
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
