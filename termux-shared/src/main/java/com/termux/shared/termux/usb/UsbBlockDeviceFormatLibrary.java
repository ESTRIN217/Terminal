package com.termux.shared.termux.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure functions for USB mass-storage (block device) inventory formatting, volume id
 * parsing, JSON serialization, and file-bridge path encoding (spec 021).
 *
 * <p>Like {@link UsbDeviceFormatLibrary}, all methods are stateless, pure, and have
 * zero dependencies on the Android SDK or Android runtime stubs so they can run
 * directly on any JVM via standard JUnit tests. Floating point math is never used
 * for sizes: capacities are rendered with integer arithmetic only.</p>
 */
public final class UsbBlockDeviceFormatLibrary {

    private static final String LOG_TAG = "UsbBlockDeviceFormatLibrary";

    /** USB class/interface class code for mass storage devices. */
    private static final int USB_CLASS_MASS_STORAGE = 0x08;

    /** Column widths of the lsblk-ish table header (RF-1). */
    private static final String LSBLK_HEADER = "NAME      SIZE    TYPE          MOUNTPOINT";

    /** Fixed informational notice appended by {@link #formatLsblk} (RF-2). */
    private static final String LSBLK_NOTICE =
        "lsblk (informational): inventory comes from the app USB snapshot;"
            + " /sys/dev/block is not readable by untrusted_app (SELinux),"
            + " so this is not live kernel state of the host.";

    /** {@code XXXX-YYYY} FAT/exFAT volume id pattern. */
    private static final Pattern VOLUME_ID_PATTERN =
        Pattern.compile("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$");

    /**
     * Characters kept verbatim by {@link #encodePathForBridge}. RFC 3986 unreserved
     * characters plus {@code /} (paths) so full file routes stay human-readable.
     */
    private static final String PATH_SAFE_CHARS = "-_.~/:@!$&'()*+,;=";

    private UsbBlockDeviceFormatLibrary() {
        // Static utility class.
    }

    /**
     * Data model representing a USB mass-storage (block device) entry for the
     * informational lsblk inventory and the {@code /tmp/usb_devices.json} snapshot
     * consumed by T6.
     */
    public static final class UsbBlockDevice {
        /** Device-like name shown in the table (e.g. {@code sda}). */
        @NonNull public final String name;
        /** Capacity in bytes (no floats allowed anywhere downstream). */
        public final long capacityBytes;
        /** {@code true} if any USB class (device or interface) is 0x08. */
        public final boolean massStorage;
        /** FAT/exFAT volume id ({@code XXXX-YYYY}) if known, else {@code null}. */
        @Nullable public final String volumeId;
        /** Framework mountpoint under {@code /storage/XXXX-YYYY} if mounted, else {@code null}. */
        @Nullable public final String mountpoint;

        public UsbBlockDevice(@NonNull String name,
                              long capacityBytes,
                              boolean massStorage,
                              @Nullable String volumeId,
                              @Nullable String mountpoint) {
            this.name = name != null ? name : "";
            this.capacityBytes = capacityBytes;
            this.massStorage = massStorage;
            this.volumeId = volumeId != null && !volumeId.trim().isEmpty() ? volumeId.trim() : null;
            this.mountpoint = mountpoint != null && !mountpoint.trim().isEmpty() ? mountpoint.trim() : null;
        }
    }

    /**
     * Decide whether a UsbDeviceDescriptor-like POJO is a mass-storage device
     * using USB class codes (device class or any interface class equal to 0x08).
     *
     * @param deviceClass the USB device class ({@code bDeviceClass})
     * @param interfaceClasses array of interface classes ({@code bInterfaceClass}
     *        of each interface), may be {@code null} or empty
     * @return {@code true} if the device or any of its interfaces is class 0x08.
     */
    public static boolean isMassStorage(int deviceClass, @Nullable int[] interfaceClasses) {
        if (deviceClass == USB_CLASS_MASS_STORAGE) return true;
        if (interfaceClasses == null) return false;
        for (int c : interfaceClasses) {
            if (c == USB_CLASS_MASS_STORAGE) return true;
        }
        return false;
    }

    /**
     * Format a capacity in bytes as an integer-only lsblk-ish size
     * ({@code G}, {@code M}, {@code K} or raw bytes with {@code B} suffix),
     * always flooring: no floating point is used (RF-1).
     *
     * @param capacityBytes capacity in bytes; negative values are treated as {@code 0}.
     * @return e.g. {@code "16G"}, {@code "512M"}, {@code "1K"}, {@code "1023B"}.
     */
    @NonNull
    public static String formatCapacityBytes(long capacityBytes) {
        if (capacityBytes < 0) capacityBytes = 0;
        long gib = capacityBytes / (1024L * 1024L * 1024L);
        if (gib > 0) return gib + "G";
        long mib = capacityBytes / (1024L * 1024L);
        if (mib > 0) return mib + "M";
        long kib = capacityBytes / 1024L;
        if (kib > 0) return kib + "K";
        return capacityBytes + "B";
    }

    /**
     * Format the informational lsblk-ish table (RF-1) with the limitation notice
     * appended (RF-2). Works on plain POJOs: never reads sysfs (RNF-4).
     *
     * @param devices list of {@link UsbBlockDevice}; may be {@code null} or empty,
     *        in which case only the header and the notice are printed.
     * @return the full table text (no trailing newline).
     */
    @NonNull
    public static String formatLsblk(@Nullable List<UsbBlockDevice> devices) {
        StringBuilder sb = new StringBuilder();
        sb.append(LSBLK_HEADER);
        sb.append('\n').append('\n');
        if (devices != null) {
            for (UsbBlockDevice d : devices) {
                if (d == null) continue;
                sb.append(pad(d.name, 10));
                sb.append(pad(formatCapacityBytes(d.capacityBytes), 8));
                sb.append(pad(d.massStorage ? "mass-storage" : "usb", 14));
                sb.append(d.mountpoint != null ? d.mountpoint : "-");
                sb.append('\n');
            }
        }
        sb.append(LSBLK_NOTICE);
        return sb.toString();
    }

    /**
     * Extract a FAT/exFAT volume id ({@code XXXX-YYYY}) from a path segment such
     * as {@code /storage/1A2B-3C4D/DCIM/x.jpg}, validating the format (RF-4).
     *
     * @param path candidate path or bare volume id.
     * @return the volume id upper-cased, or {@code null} if no valid ids found.
     */
    @Nullable
    public static String parseVolumeId(@Nullable String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        if (trimmed.isEmpty()) return null;
        // Try each path segment from the end.
        List<String> candidates = new ArrayList<>();
        if (VOLUME_ID_PATTERN.matcher(trimmed).matches()) candidates.add(trimmed);
        String work = trimmed;
        int idx;
        while ((idx = work.lastIndexOf('/')) >= 0) {
            String segment = work.substring(idx + 1);
            if (VOLUME_ID_PATTERN.matcher(segment).matches()) candidates.add(segment);
            if (candidates.size() == 2) break; // enough for nested /storage/ID/...
            work = work.substring(0, idx);
        }
        if (candidates.size() == 1) return candidates.get(0).toUpperCase(Locale.ROOT);
        if (candidates.size() > 1) {
            // Prefer the deepest (last) match.
            return candidates.get(0).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * Serialize a list of {@link UsbBlockDevice} into a JSON string using pure Java
     * (same hand-rolled style as {@link UsbDeviceFormatLibrary#toJson}).
     *
     * @param devices devices to serialize; may be {@code null}.
     * @return JSON array text.
     */
    @NonNull
    public static String toJson(@Nullable List<UsbBlockDevice> devices) {
        if (devices == null || devices.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (UsbBlockDevice d : devices) {
            if (d == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"name\":").append(escapeJsonString(d.name)).append(",");
            sb.append("\"capacityBytes\":").append(d.capacityBytes).append(",");
            sb.append("\"massStorage\":").append(d.massStorage).append(",");
            if (d.volumeId != null) {
                sb.append("\"volumeId\":").append(escapeJsonString(d.volumeId)).append(",");
            }
            if (d.mountpoint != null) {
                sb.append("\"mountpoint\":").append(escapeJsonString(d.mountpoint)).append(",");
            }
            sb.append("\"type\":\"").append(d.massStorage ? "mass-storage" : "usb").append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize a JSON string produced by {@link #toJson} back into a list of
     * {@link UsbBlockDevice} (round-trip validated by tests). Entries missing the
     * {@code name} field are skipped.
     *
     * @param json JSON array text; may be {@code null} or malformed.
     * @return parsed list of devices (never {@code null}; empty for malformed input).
     */
    @NonNull
    public static List<UsbBlockDevice> fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return Collections.emptyList();

        List<UsbBlockDevice> result = new ArrayList<>();
        int i = 0;
        int len = trimmed.length();
        while (i < len) {
            int openBrace = trimmed.indexOf('{', i);
            if (openBrace == -1) break;
            int closeBrace = findMatchingBrace(trimmed, openBrace);
            if (closeBrace == -1) break;

            String objContent = trimmed.substring(openBrace + 1, closeBrace);
            UsbBlockDevice dev = parseObjectContent(objContent);
            if (dev != null) {
                result.add(dev);
            }
            i = closeBrace + 1;
        }
        return result;
    }

    /**
     * Encode a path for the guest↔host file bridge (RF-4): UTF-8 percent-encoding
     * of every byte, keeping RFC 3986 path-safe characters verbatim so spaces,
     * UTF-8 names and reserved characters cannot corrupt the protocol.
     *
     * @param path the raw path; may be {@code null}.
     * @return the encoded path, or {@code null} if {@code path} is {@code null}.
     */
    @Nullable
    public static String encodePathForBridge(@Nullable String path) {
        if (path == null) return null;
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 8);
        for (int j = 0; j < bytes.length; j++) {
            byte b = bytes[j];
            char c = (char) (b & 0xFF);
            boolean safe = (b >= 'a' && b <= 'z')
                || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9')
                || (c < 0x80 && PATH_SAFE_CHARS.indexOf(c) >= 0);
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append(String.format(Locale.ROOT, "%%%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    /**
     * Decode a path encoded by {@link #encodePathForBridge} (RF-4). Multi-byte
     * UTF-8 sequences are reassembled; malformed escapes fail the whole decode.
     *
     * @param encoded the percent-encoded path; may be {@code null}.
     * @return the decoded path, or {@code null} if invalid or {@code null} input.
     */
    @Nullable
    public static String decodePathForBridge(@Nullable String encoded) {
        if (encoded == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '%') {
                if (c > 0x7F) return null; // decoder input must be ASCII percent-encoding
                out.write((byte) c);
                continue;
            }
            if (i + 2 >= encoded.length()) return null;
            int hi = hexVal(encoded.charAt(i + 1));
            int lo = hexVal(encoded.charAt(i + 2));
            if (hi < 0 || lo < 0) return null;
            out.write((byte) ((hi << 4) | lo));
            i += 2;
        }
        try {
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + (width > 0 ? "" : "");
        StringBuilder sb = new StringBuilder(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    @Nullable
    private static UsbBlockDevice parseObjectContent(String content) {
        Map<String, String> fields = new HashMap<>();
        int i = 0;
        int len = content.length();
        while (i < len) {
            int quoteStart = content.indexOf('"', i);
            if (quoteStart == -1) break;
            int quoteEnd = findClosingQuote(content, quoteStart);
            if (quoteEnd == -1) break;
            String key = unescapeJsonString(content.substring(quoteStart + 1, quoteEnd));

            int colon = content.indexOf(':', quoteEnd + 1);
            if (colon == -1) break;

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

        String name = fields.get("name");
        if (name == null) return null;

        long capacity = parseLong(fields.get("capacityBytes"), 0);
        boolean massStorage = Boolean.parseBoolean(fields.get("massStorage"));
        String volumeId = fields.get("volumeId");
        String mountpoint = fields.get("mountpoint");

        return new UsbBlockDevice(name, capacity, massStorage, volumeId, mountpoint);
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

    private static long parseLong(@Nullable String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
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
