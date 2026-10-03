package com.termux.terminal;

/**
 * A single inline terminal image (OSC 1337 / kitty graphics).
 * <p>
 * The registry lives on {@link TerminalEmulator}; cells reference it through the
 * side-band {@link TerminalRow#getImage(int)} array (1-based index, 0 = none).
 * Encoded bytes are stored raw so this module stays free of {@code android.graphics}
 * — renderers decode to a Bitmap and cache it themselves.
 * <p>
 * Kitty can transmit without displaying ({@code a=t}): placement fields stay
 * unset until {@link #placeAt}. Multiple placements of the same image share the
 * same {@link #encoded} array reference (no byte copy).
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
    /** External (transcript-aware) row of the top-left cell of the placement, or -1 when not placed. */
    public int startRow;
    /** Screen column of the top-left cell of the placement, or -1 when not placed. */
    public int startCol;
    /** Placement width in terminal cells (0 when not placed). */
    public int cellsW;
    /** Placement height in terminal cells (0 when not placed). */
    public int cellsH;
    /**
     * Whether this placement was stamped into the alternate screen buffer.
     * Row shifts (scroll, insert/delete lines) only touch placements that belong
     * to the currently active buffer, so pager-style alt-screen scrolling never
     * moves main-screen placement rects.
     */
    public boolean onAltScreen;
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
     * @param startRow    external row of the placement origin, or -1
     * @param startCol    column of the placement origin, or -1
     * @param cellsW      width in cells (0 when not placed)
     * @param cellsH      height in cells (0 when not placed)
     * @param pixelWidth  intrinsic pixel width, or 0 when unknown
     * @param pixelHeight intrinsic pixel height, or 0 when unknown
     * @param pixelFormat one of {@link #FORMAT_ENCODED}, {@link #FORMAT_RGB_24}, {@link #FORMAT_RGBA_32}
     */
    public TerminalImageData(int id, byte[] encoded, int startRow, int startCol,
                             int cellsW, int cellsH, int pixelWidth, int pixelHeight,
                             int pixelFormat) {
        this.id = id;
        this.encoded = encoded;
        this.startRow = startRow;
        this.startCol = startCol;
        this.cellsW = cellsW;
        this.cellsH = cellsH;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelFormat = pixelFormat;
    }

    /**
     * Assign or replace the on-screen placement for this image.
     *
     * @param row    external row of the top-left cell
     * @param col    column of the top-left cell
     * @param cellsW placement width in cells (&gt;= 1)
     * @param cellsH placement height in cells (&gt;= 1)
     */
    public void placeAt(int row, int col, int cellsW, int cellsH) {
        placeAt(row, col, cellsW, cellsH, false);
    }

    /**
     * Assign or replace the on-screen placement for this image, recording the
     * buffer it was stamped into.
     *
     * @param row         external row of the top-left cell
     * @param col         column of the top-left cell
     * @param cellsW      placement width in cells (&gt;= 1)
     * @param cellsH      placement height in cells (&gt;= 1)
     * @param onAltScreen {@code true} when stamped into the alternate screen buffer
     */
    public void placeAt(int row, int col, int cellsW, int cellsH, boolean onAltScreen) {
        this.startRow = row;
        this.startCol = col;
        this.cellsW = cellsW;
        this.cellsH = cellsH;
        this.onAltScreen = onAltScreen;
    }

    /**
     * Whether this image currently has an on-screen placement. A placement may
     * have scrolled partway into the transcript (negative {@link #startRow})
     * and is still live there, so only the cell dimensions gate this.
     */
    public boolean isPlaced() {
        return cellsW > 0 && cellsH > 0;
    }

    /**
     * Whether an external row intersects this placement's vertical span.
     *
     * @param externalRow the row to test
     * @return {@code true} when the row is inside the placement
     */
    public boolean intersectsRow(int externalRow) {
        return isPlaced()
            && externalRow >= startRow && externalRow < startRow + cellsH;
    }

    /**
     * Whether a screen cell falls inside this placement's rectangle.
     *
     * @param externalRow external row
     * @param column      screen column
     * @return {@code true} when the cell is covered
     */
    public boolean containsCell(int externalRow, int column) {
        return isPlaced()
            && externalRow >= startRow && externalRow < startRow + cellsH
            && column >= startCol && column < startCol + cellsW;
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
