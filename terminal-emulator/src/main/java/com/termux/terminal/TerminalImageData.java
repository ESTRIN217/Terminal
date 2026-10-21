package com.termux.terminal;

/**
 * A single inline terminal image payload (OSC 1337 / kitty graphics).
 * <p>
 * The registry lives on {@link TerminalEmulator}; cells reference a
 * {@link TerminalImagePlacement} through the side-band {@link TerminalRow#getImage(int)}
 * array (1-based placement key, 0 = none), and the placement in turn references one of these
 * entries by {@link #id}. On-screen geometry (origin, destination cells, source crop,
 * {@code z}, screen buffer) lives in {@link TerminalImagePlacement}, so one payload can back
 * several simultaneous placements while its bytes are stored once (RF-36, RNF-7).
 * <p>
 * Encoded bytes are stored raw so this module stays free of {@code android.graphics} —
 * renderers decode to a Bitmap and cache it themselves.
 */
public final class TerminalImageData {

    /** Registry index (1-based), assigned by the emulator when the image is registered. */
    public final int id;
    /**
     * Image payload: PNG/JPEG bytes when {@link #pixelFormat} is
     * {@link #FORMAT_ENCODED}, or raw RGB/RGBA samples when format is
     * {@link #FORMAT_RGB_24}/{@link #FORMAT_RGBA_32}.
     */
    public final byte[] encoded;
    /** Intrinsic pixel width when known from the protocol, otherwise {@code 0}. */
    public final int pixelWidth;
    /** Intrinsic pixel height when known from the protocol, otherwise {@code 0}. */
    public final int pixelHeight;
    /**
     * Payload layout: {@link #FORMAT_ENCODED} (PNG/JPEG — decode with BitmapFactory),
     * {@link #FORMAT_RGB_24}, or {@link #FORMAT_RGBA_32} (raw samples from kitty {@code f=}).
     */
    public final int pixelFormat;

    /** Payload is a self-describing encoded image (PNG/JPEG). */
    public static final int FORMAT_ENCODED = 0;
    /** Raw 24-bit RGB, row-major, {@code pixelWidth * 3} bytes per row. */
    public static final int FORMAT_RGB_24 = 24;
    /** Raw 32-bit RGBA, row-major, {@code pixelWidth * 4} bytes per row. */
    public static final int FORMAT_RGBA_32 = 32;

    /**
     * @param id          1-based registry index
     * @param encoded     decoded image bytes (encoded or raw pixels)
     * @param pixelWidth  intrinsic pixel width, or 0 when unknown
     * @param pixelHeight intrinsic pixel height, or 0 when unknown
     * @param pixelFormat one of {@link #FORMAT_ENCODED}, {@link #FORMAT_RGB_24}, {@link #FORMAT_RGBA_32}
     */
    public TerminalImageData(int id, byte[] encoded, int pixelWidth, int pixelHeight,
                             int pixelFormat) {
        this.id = id;
        this.encoded = encoded;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelFormat = pixelFormat;
    }

    /**
     * Decode raw RGB/RGBA payload to packed ARGB {@code int}s for renderers.
     * Only valid when {@link #pixelFormat} is {@link #FORMAT_RGB_24} or
     * {@link #FORMAT_RGBA_32} and {@link #pixelWidth}/{@link #pixelHeight} are set.
     *
     * @return row-major ARGB pixels, or {@code null} when not raw / invalid
     */
    public int[] decodeRawArgb() {
        if (pixelFormat != FORMAT_RGB_24 && pixelFormat != FORMAT_RGBA_32) return null;
        final int w = pixelWidth;
        final int h = pixelHeight;
        if (w <= 0 || h <= 0 || encoded == null) return null;
        final int bpp = (pixelFormat == FORMAT_RGBA_32) ? 4 : 3;
        if (encoded.length < w * h * bpp) return null;
        final int[] out = new int[w * h];
        int p = 0;
        for (int i = 0; i < out.length; i++) {
            final int r = encoded[p++] & 0xFF;
            final int g = encoded[p++] & 0xFF;
            final int b = encoded[p++] & 0xFF;
            final int a = (bpp == 4) ? (encoded[p++] & 0xFF) : 0xFF;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }
}
