package com.termux.terminal;

/**
 * Minimal RFC 4648 Base64 decoder for inline image payloads.
 * <p>
 * {@code android.util.Base64} is unavailable in {@code terminal-emulator} unit tests
 * ({@code unitTests.isReturnDefaultValues = true} makes it return {@code null}), so
 * image sequences use this pure-Java decoder instead.
 */
final class ImageBase64 {

    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) DECODE[i] = -1;
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) DECODE[alphabet.charAt(i)] = i;
    }

    private ImageBase64() {
    }

    /**
     * Decode standard Base64 (with or without padding). Whitespace is skipped;
     * any other invalid character fails the whole decode.
     *
     * @param encoded the Base64 text
     * @return the decoded bytes, or {@code null} when the input is invalid
     */
    static byte[] decode(String encoded) {
        if (encoded == null) return null;
        // Strip whitespace first so length math is simple.
        StringBuilder buf = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            buf.append(c);
        }
        final String s = buf.toString();
        int len = s.length();
        while (len > 0 && s.charAt(len - 1) == '=') len--;
        if (len == 0) return new byte[0];
        if (len % 4 == 1) return null;

        final int outLen = (len * 3) / 4;
        final byte[] out = new byte[outLen];
        int outPos = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            final int v = (c < 128) ? DECODE[c] : -1;
            if (v < 0) return null;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outPos++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        if (outPos != outLen) return null;
        return out;
    }

    /**
     * Encode bytes as standard Base64 with padding (test fixtures / round-trips).
     *
     * @param data raw bytes
     * @return Base64 text
     */
    static String encode(byte[] data) {
        if (data == null) return "";
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        final StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            final int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(alphabet.charAt((n >> 18) & 63));
            sb.append(alphabet.charAt((n >> 12) & 63));
            sb.append(alphabet.charAt((n >> 6) & 63));
            sb.append(alphabet.charAt(n & 63));
            i += 3;
        }
        final int rem = data.length - i;
        if (rem == 1) {
            final int n = (data[i] & 0xFF) << 16;
            sb.append(alphabet.charAt((n >> 18) & 63));
            sb.append(alphabet.charAt((n >> 12) & 63));
            sb.append("==");
        } else if (rem == 2) {
            final int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(alphabet.charAt((n >> 18) & 63));
            sb.append(alphabet.charAt((n >> 12) & 63));
            sb.append(alphabet.charAt((n >> 6) & 63));
            sb.append('=');
        }
        return sb.toString();
    }
}
