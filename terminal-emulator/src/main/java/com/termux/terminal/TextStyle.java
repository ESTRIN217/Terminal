package com.termux.terminal;

/**
 * <p>
 * Encodes effects, foreground and background colors into a 64 bit long, which are stored for each cell in a terminal
 * row in {@link TerminalRow#mStyle}.
 * </p>
 * <p>
 * The bit layout is:
 * </p>
 * - 16 flags (11 currently used).
 * - 24 for foreground color (only 9 first bits if a color index).
 * - 24 for background color (only 9 first bits if a color index).
 */
public final class TextStyle {

    public final static int CHARACTER_ATTRIBUTE_BOLD = 1;
    public final static int CHARACTER_ATTRIBUTE_ITALIC = 1 << 1;
    public final static int CHARACTER_ATTRIBUTE_UNDERLINE = 1 << 2;
    public final static int CHARACTER_ATTRIBUTE_BLINK = 1 << 3;
    public final static int CHARACTER_ATTRIBUTE_INVERSE = 1 << 4;
    public final static int CHARACTER_ATTRIBUTE_INVISIBLE = 1 << 5;
    public final static int CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 << 6;
    /**
     * The selective erase control functions (DECSED and DECSEL) can only erase characters defined as erasable.
     * <p>
     * This bit is set if DECSCA (Select Character Protection Attribute) has been used to define the characters that
     * come after it as erasable from the screen.
     * </p>
     */
    public final static int CHARACTER_ATTRIBUTE_PROTECTED = 1 << 7;
    /** Dim colors. Also known as faint or half intensity. */
    public final static int CHARACTER_ATTRIBUTE_DIM = 1 << 8;
    /** If true (24-bit) color is used for the cell for foreground. */
    private final static int CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND = 1 << 9;
    /** If true (24-bit) color is used for the cell for foreground. */
    private final static int CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND= 1 << 10;

    public final static int COLOR_INDEX_FOREGROUND = 256;
    public final static int COLOR_INDEX_BACKGROUND = 257;
    public final static int COLOR_INDEX_CURSOR = 258;

    /** The 256 standard color entries and the three special (foreground, background and cursor) ones. */
    public final static int NUM_INDEXED_COLORS = 259;

    /** Normal foreground and background colors and no effects. */
    final static long NORMAL = encode(COLOR_INDEX_FOREGROUND, COLOR_INDEX_BACKGROUND, 0);

    static long encode(int foreColor, int backColor, int effect) {
        long result = effect & 0b111111111;
        if ((0xff000000 & foreColor) == 0xff000000) {
            // 24-bit color.
            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND | ((foreColor & 0x00ffffffL) << 40L);
        } else {
            // Indexed color.
            result |= (foreColor & 0b111111111L) << 40;
        }
        if ((0xff000000 & backColor) == 0xff000000) {
            // 24-bit color.
            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND | ((backColor & 0x00ffffffL) << 16L);
        } else {
            // Indexed color.
            result |= (backColor & 0b111111111L) << 16L;
        }

        return result;
    }

    public static int decodeForeColor(long style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND) == 0) {
            return (int) ((style >>> 40) & 0b111111111L);
        } else {
            return 0xff000000 | (int) ((style >>> 40) & 0x00ffffffL);
        }

    }

    public static int decodeBackColor(long style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND) == 0) {
            return (int) ((style >>> 16) & 0b111111111L);
        } else {
            return 0xff000000 | (int) ((style >>> 16) & 0x00ffffffL);
        }
    }

    public static int decodeEffect(long style) {
        return (int) (style & 0b11111111111);
    }

    /**
     * Resolve the effective background color of a cell, mirroring the legacy
     * renderer's color logic in {@code TerminalRenderer.drawTextRun} (palette
     * lookup, bold-bright foreground, reverse-video swap).
     * <p>
     * Used to blank a cell before painting an image over it (kitty graphics spec:
     * transparent image regions must show the cell background, never the text
     * glyph underneath).
     * </p>
     *
     * @param style         the cell style ({@link TerminalRow#mStyle} encoding)
     * @param paletteColors the emulator indexed colors ({@link #NUM_INDEXED_COLORS} entries)
     * @param reverseVideo  whether the emulator is in reverse-video mode (or the cell is
     *                      inside the selection / block cursor — the combined flag the
     *                      legacy renderer passes to {@code drawTextRun})
     * @return the resolved background color as ARGB (never a palette index)
     */
    public static int effectiveBackgroundColor(long style, int[] paletteColors, boolean reverseVideo) {
        int foreColor = decodeForeColor(style);
        int backColor = decodeBackColor(style);
        final int effect = decodeEffect(style);
        final boolean bold = (effect & (CHARACTER_ATTRIBUTE_BOLD | CHARACTER_ATTRIBUTE_BLINK)) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8).
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = paletteColors[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = paletteColors[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set.
        final boolean reverseVideoHere = reverseVideo ^ ((effect & CHARACTER_ATTRIBUTE_INVERSE) != 0);
        return reverseVideoHere ? foreColor : backColor;
    }

}
