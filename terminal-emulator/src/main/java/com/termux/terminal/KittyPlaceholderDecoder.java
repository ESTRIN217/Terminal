package com.termux.terminal;

/**
 * Decodes kitty unicode-placeholder cells ({@code U+10EEEE} + row/column
 * diacritics + foreground-encoded image id) into an image id and grid position.
 *
 * <p>Shared by the legacy ({@link com.termux.view.TerminalRenderer}) and Compose
 * renderers. Pure Java / no Android dependencies so it stays unit-testable in
 * {@code terminal-emulator}.</p>
 *
 * <p>Spec: <a href="https://sw.kovidgoyal.net/kitty/graphics-protocol/#unicode-placeholders">graphics-protocol §Unicode placeholders</a>.
 * The diacritics are the 297 combining marks of kitty's {@code rowcolumn-diacritics.txt}
 * (index 0 = {@code U+0305}, 1 = {@code U+030D}, 2 = {@code U+030E}); after the
 * placeholder they appear in order: row, column, most-significant image-id byte.
 * Missing diacritics inherit from the previous placeholder cell to the left
 * (same foreground required).</p>
 */
public final class KittyPlaceholderDecoder {

    /** The private-use placeholder code point that stands in for an image cell. */
    public static final int PLACEHOLDER_CODE_POINT = 0x10EEEE;

    /**
     * {@code rowcolumn-diacritics.txt} in order; array index is the value the
     * diacritic encodes. Generated from kitty's gen/rowcolumn-diacritics.txt
     * (Unicode 6.0.0 combining marks, class 230, no decomposition).
     */
    private static final int[] DIACRITICS = {
    0x00305, 0x0030D, 0x0030E, 0x00310, 0x00312, 0x0033D, 0x0033E, 0x0033F, 0x00346, 0x0034A,
    0x0034B, 0x0034C, 0x00350, 0x00351, 0x00352, 0x00357, 0x0035B, 0x00363, 0x00364, 0x00365,
    0x00366, 0x00367, 0x00368, 0x00369, 0x0036A, 0x0036B, 0x0036C, 0x0036D, 0x0036E, 0x0036F,
    0x00483, 0x00484, 0x00485, 0x00486, 0x00487, 0x00592, 0x00593, 0x00594, 0x00595, 0x00597,
    0x00598, 0x00599, 0x0059C, 0x0059D, 0x0059E, 0x0059F, 0x005A0, 0x005A1, 0x005A8, 0x005A9,
    0x005AB, 0x005AC, 0x005AF, 0x005C4, 0x00610, 0x00611, 0x00612, 0x00613, 0x00614, 0x00615,
    0x00616, 0x00617, 0x00657, 0x00658, 0x00659, 0x0065A, 0x0065B, 0x0065D, 0x0065E, 0x006D6,
    0x006D7, 0x006D8, 0x006D9, 0x006DA, 0x006DB, 0x006DC, 0x006DF, 0x006E0, 0x006E1, 0x006E2,
    0x006E4, 0x006E7, 0x006E8, 0x006EB, 0x006EC, 0x00730, 0x00732, 0x00733, 0x00735, 0x00736,
    0x0073A, 0x0073D, 0x0073F, 0x00740, 0x00741, 0x00743, 0x00745, 0x00747, 0x00749, 0x0074A,
    0x007EB, 0x007EC, 0x007ED, 0x007EE, 0x007EF, 0x007F0, 0x007F1, 0x007F3, 0x00816, 0x00817,
    0x00818, 0x00819, 0x0081B, 0x0081C, 0x0081D, 0x0081E, 0x0081F, 0x00820, 0x00821, 0x00822,
    0x00823, 0x00825, 0x00826, 0x00827, 0x00829, 0x0082A, 0x0082B, 0x0082C, 0x0082D, 0x00951,
    0x00953, 0x00954, 0x00F82, 0x00F83, 0x00F86, 0x00F87, 0x0135D, 0x0135E, 0x0135F, 0x017DD,
    0x0193A, 0x01A17, 0x01A75, 0x01A76, 0x01A77, 0x01A78, 0x01A79, 0x01A7A, 0x01A7B, 0x01A7C,
    0x01B6B, 0x01B6D, 0x01B6E, 0x01B6F, 0x01B70, 0x01B71, 0x01B72, 0x01B73, 0x01CD0, 0x01CD1,
    0x01CD2, 0x01CDA, 0x01CDB, 0x01CE0, 0x01DC0, 0x01DC1, 0x01DC3, 0x01DC4, 0x01DC5, 0x01DC6,
    0x01DC7, 0x01DC8, 0x01DC9, 0x01DCB, 0x01DCC, 0x01DD1, 0x01DD2, 0x01DD3, 0x01DD4, 0x01DD5,
    0x01DD6, 0x01DD7, 0x01DD8, 0x01DD9, 0x01DDA, 0x01DDB, 0x01DDC, 0x01DDD, 0x01DDE, 0x01DDF,
    0x01DE0, 0x01DE1, 0x01DE2, 0x01DE3, 0x01DE4, 0x01DE5, 0x01DE6, 0x01DFE, 0x020D0, 0x020D1,
    0x020D4, 0x020D5, 0x020D6, 0x020D7, 0x020DB, 0x020DC, 0x020E1, 0x020E7, 0x020E9, 0x020F0,
    0x02CEF, 0x02CF0, 0x02CF1, 0x02DE0, 0x02DE1, 0x02DE2, 0x02DE3, 0x02DE4, 0x02DE5, 0x02DE6,
    0x02DE7, 0x02DE8, 0x02DE9, 0x02DEA, 0x02DEB, 0x02DEC, 0x02DED, 0x02DEE, 0x02DEF, 0x02DF0,
    0x02DF1, 0x02DF2, 0x02DF3, 0x02DF4, 0x02DF5, 0x02DF6, 0x02DF7, 0x02DF8, 0x02DF9, 0x02DFA,
    0x02DFB, 0x02DFC, 0x02DFD, 0x02DFE, 0x02DFF, 0x0A66F, 0x0A67C, 0x0A67D, 0x0A6F0, 0x0A6F1,
    0x0A8E0, 0x0A8E1, 0x0A8E2, 0x0A8E3, 0x0A8E4, 0x0A8E5, 0x0A8E6, 0x0A8E7, 0x0A8E8, 0x0A8E9,
    0x0A8EA, 0x0A8EB, 0x0A8EC, 0x0A8ED, 0x0A8EE, 0x0A8EF, 0x0A8F0, 0x0A8F1, 0x0AAB0, 0x0AAB2,
    0x0AAB3, 0x0AAB7, 0x0AAB8, 0x0AABE, 0x0AABF, 0x0AAC1, 0x0FE20, 0x0FE21, 0x0FE22, 0x0FE23,
    0x0FE24, 0x0FE25, 0x0FE26, 0x10A0F, 0x10A38, 0x1D185, 0x1D186, 0x1D187, 0x1D188, 0x1D189,
    0x1D1AA, 0x1D1AB, 0x1D1AC, 0x1D1AD, 0x1D242, 0x1D243, 0x1D244
    };

    private KittyPlaceholderDecoder() {
    }

    /**
     * @param codePoint combining mark to look up
     * @return the value the diacritic encodes (0..296), or {@code -1} when the
     * code point is not a row/column diacritic
     */
    public static int diacriticIndex(int codePoint) {
        // Linear scan: 297 entries, decode is only reached for placeholder cells.
        for (int i = 0; i < DIACRITICS.length; i++)
            if (DIACRITICS[i] == codePoint) return i;
        return -1;
    }

    /** Decoded target of one placeholder cell. */
    public static final class Target {
        /** Full image id: {@code (msb << 24) | foreground-id}. */
        public final int imageId;
        /** Row inside the virtual placement's cell grid (0-based). */
        public final int gridRow;
        /** Column inside the virtual placement's cell grid (0-based). */
        public final int gridCol;

        /**
         * @param imageId  full32 image id from fg color + msb diacritic
         * @param gridRow  grid row
         * @param gridCol  grid column
         */
        public Target(int imageId, int gridRow, int gridCol) {
            this.imageId = imageId;
            this.gridRow = gridRow;
            this.gridCol = gridCol;
        }
    }

    /** Visitor for placeholder cells found while walking a row. */
    public interface CellVisitor {
        /**
         * @param column  screen column of the placeholder cell
         * @param target  decoded image id + grid position
         */
        void visit(int column, Target target);
    }

    /**
     * Left-to-right inheritance state for placeholder cells of one screen row.
     * Reset at the start of each row unless the previous row wrapped.
     */
    public static final class RowState {
        boolean hasPrev;
        int prevRow;
        int prevCol;
        int prevMsb;
        /** Foreground as {@link TextStyle#decodeForeColor(long)} (compared verbatim). */
        int prevFg;

        /** Drop inherited state (start of a fresh logical line). */
        public void reset() {
            hasPrev = false;
        }
    }

    /**
     * Decode one placeholder cell. {@code charIndex} points at the
     * {@link #PLACEHOLDER_CODE_POINT}; following zero-width code points in the
     * same cell are the row/column/msb diacritics.
     *
     * @param style      cell style (foreground encodes the low 24 image id bits)
     * @param text       row text buffer
     * @param charIndex  index of the placeholder code point in {@code text}
     * @param charsUsed  valid length of {@code text}
     * @param state      row inheritance state; updated on success
     * @return the decoded target, or {@code null} when the cell cannot be
     * resolved (renderers then keep the plain glyph as fallback)
     */
    public static Target decode(long style, char[] text, int charIndex, int charsUsed, RowState state) {
        final int fgRaw = TextStyle.decodeForeColor(style);
        final int low24;
        if ((fgRaw & 0xff000000) == 0xff000000) {
            low24 = fgRaw & 0x00ffffff; // SGR 38;2;R;G;B → 24-bit id
        } else if (fgRaw >= 0 && fgRaw <= 0xff) {
            low24 = fgRaw;              // SGR 38;5;N → 8-bit id
        } else {
            return null;                // default/near-default fg: not a valid id
        }

        int rowD = -1, colD = -1, msbD = -1;
        int slot = 0;
        int idx = charIndex + (Character.isHighSurrogate(text[charIndex]) ? 2 : 1);
        while (idx < charsUsed && WcWidth.width(text, idx) <= 0) {
            final int value = diacriticIndex(codePointAt(text, idx));
            if (slot == 0) rowD = value;
            else if (slot == 1) colD = value;
            else if (slot == 2) msbD = value;
            slot++;
            idx += (Character.isHighSurrogate(text[idx]) ? 2 : 1);
        }

        final boolean hasRow = rowD >= 0;
        final boolean hasCol = colD >= 0;
        final int msb = msbD >= 0 ? msbD : -1;
        int row, col, msbByte;

        if (hasRow && hasCol) {
            row = rowD;
            col = colD;
            // Rule 3: msb inherited only when the cell to the left is exactly
            // (row, col-1) with the same foreground.
            if (msb >= 0) msbByte = msb;
            else if (state.hasPrev && state.prevFg == fgRaw && state.prevRow == rowD && state.prevCol == colD - 1)
                msbByte = state.prevMsb;
            else
                msbByte = 0;
        } else if (hasRow) {
            // Rule 2: column = left + 1, msb inherited; the first cell of a row
            // starts at column 0 (spec's 2×3 example relies on this).
            if (state.hasPrev && state.prevFg == fgRaw && state.prevRow == rowD) {
                row = rowD;
                col = state.prevCol + 1;
                msbByte = (msb >= 0) ? msb : state.prevMsb;
            } else if (!state.hasPrev) {
                row = rowD;
                col = 0;
                msbByte = (msb >= 0) ? msb : 0;
            } else {
                return null;
            }
        } else if (!hasCol) {
            // Rule 1: no diacritics at all — full inheritance from the left.
            if (!state.hasPrev || state.prevFg != fgRaw) return null;
            row = state.prevRow;
            col = state.prevCol + 1;
            msbByte = (msb >= 0) ? msb : state.prevMsb;
        } else {
            // Column without row: not covered by the spec; best effort from the left.
            if (!state.hasPrev || state.prevFg != fgRaw) return null;
            row = state.prevRow;
            col = colD;
            msbByte = (msb >= 0) ? msb : state.prevMsb;
        }

        state.hasPrev = true;
        state.prevRow = row;
        state.prevCol = col;
        state.prevMsb = msbByte;
        state.prevFg = fgRaw;
        return new Target((msbByte << 24) | low24, row, col);
    }

    /**
     * Walk a screen row's cells left to right and report every resolvable
     * placeholder. Combining characters are treated as part of their cell,
     * mirroring the text-run walks in the renderers.
     *
     * @param lineObject the row to scan
     * @param columns    screen columns
     * @param state      inheritance state (caller resets per logical line)
     * @param visitor    receives each placeholder cell
     */
    public static void collectRow(TerminalRow lineObject, int columns, RowState state, CellVisitor visitor) {
        final char[] text = lineObject.mText;
        final int charsUsed = lineObject.getSpaceUsed();
        int charIndex = 0;
        for (int column = 0; column < columns && charIndex < charsUsed; ) {
            final char c = text[charIndex];
            final boolean surrogate = Character.isHighSurrogate(c);
            final int codePoint = surrogate ? Character.toCodePoint(c, text[charIndex + 1]) : c;
            final int width = WcWidth.width(codePoint);
            if (codePoint == PLACEHOLDER_CODE_POINT) {
                final Target target = decode(lineObject.getStyle(column), text, charIndex, charsUsed, state);
                if (target != null) visitor.visit(column, target);
            }
            if (width > 0) column += width;
            charIndex += surrogate ? 2 : 1;
            while (charIndex < charsUsed && WcWidth.width(text, charIndex) <= 0) {
                // Combining chars belong to the current cell (same rule the
                // emulator uses when emitting them).
                charIndex += Character.isHighSurrogate(text[charIndex]) ? 2 : 1;
            }
        }
    }

    private static int codePointAt(char[] text, int index) {
        final char c = text[index];
        return Character.isHighSurrogate(c) ? Character.toCodePoint(c, text[index + 1]) : c;
    }
}
