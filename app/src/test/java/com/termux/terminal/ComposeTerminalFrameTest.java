package com.termux.terminal;

import com.termux.terminal.compose.ComposeTerminalFrame;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for the pure run-grouping and color-resolution logic of the experimental
 * Compose Canvas renderer spike ({@code ComposeTerminalFrame}).
 *
 * Lives in the {@code com.termux.terminal} package to access package-private helpers
 * ({@link TextStyle#encode}, {@link TextStyle#NORMAL}) the same way the emulator tests do.
 */
public class ComposeTerminalFrameTest {

    private static TerminalRow asciiRow(int columns, String text, long style) {
        TerminalRow row = new TerminalRow(columns, TextStyle.NORMAL);
        for (int col = 0; col < columns && col < text.length(); col++) {
            row.setChar(col, text.charAt(col), style);
        }
        return row;
    }

    private static int[] distinctPalette() {
        int[] palette = new int[TextStyle.NUM_INDEXED_COLORS];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = 0xFF000000 | (i & 0x00FFFFFF);
        }
        return palette;
    }

    @Test
    public void testUniformLine_producesSingleRun() {
        TerminalRow row = asciiRow(4, "abcd", TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 4, -1, -1, -1, true, codePoint -> false);

        Assert.assertEquals(1, runs.size());
        ComposeTerminalFrame.TextRun run = runs.get(0);
        Assert.assertEquals(0, run.getStartColumn());
        Assert.assertEquals(4, run.getColumnWidth());
        Assert.assertEquals(0, run.getStartCharIndex());
        Assert.assertEquals(4, run.getCharCount());
        Assert.assertFalse(run.getInCursor());
        Assert.assertFalse(run.getInSelection());
    }

    @Test
    public void testStyleChange_splitsRuns() {
        long plain = TextStyle.encode(
            TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND, 0);
        long bold = TextStyle.encode(
            TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.CHARACTER_ATTRIBUTE_BOLD);
        TerminalRow row = new TerminalRow(4, plain);
        row.setChar(0, 'a', plain);
        row.setChar(1, 'b', plain);
        row.setChar(2, 'c', bold);
        row.setChar(3, 'd', bold);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 4, -1, -1, -1, true, codePoint -> false);

        Assert.assertEquals(2, runs.size());
        Assert.assertEquals(0, runs.get(0).getStartColumn());
        Assert.assertEquals(2, runs.get(0).getColumnWidth());
        Assert.assertEquals(2, runs.get(1).getStartColumn());
        Assert.assertEquals(2, runs.get(1).getColumnWidth());
        Assert.assertEquals(2, runs.get(1).getStartCharIndex());
        Assert.assertEquals(2, runs.get(1).getCharCount());
    }

    @Test
    public void testCursorCell_splitsIntoThreeRuns() {
        TerminalRow row = asciiRow(5, "abcde", TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 5, 2, -1, -1, true, codePoint -> false);

        Assert.assertEquals(3, runs.size());
        Assert.assertFalse(runs.get(0).getInCursor());
        Assert.assertEquals(2, runs.get(0).getColumnWidth());
        Assert.assertTrue(runs.get(1).getInCursor());
        Assert.assertEquals(2, runs.get(1).getStartColumn());
        Assert.assertEquals(1, runs.get(1).getColumnWidth());
        Assert.assertFalse(runs.get(2).getInCursor());
        Assert.assertEquals(3, runs.get(2).getStartColumn());
    }

    @Test
    public void testSelection_splitsRuns() {
        TerminalRow row = asciiRow(5, "abcde", TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 5, -1, 1, 2, true, codePoint -> false);

        Assert.assertEquals(3, runs.size());
        Assert.assertFalse(runs.get(0).getInSelection());
        Assert.assertTrue(runs.get(1).getInSelection());
        Assert.assertEquals(1, runs.get(1).getStartColumn());
        Assert.assertEquals(2, runs.get(1).getColumnWidth());
        Assert.assertFalse(runs.get(2).getInSelection());
    }

    @Test
    public void testLigaturesDisabled_splitsPerCodePoint() {
        TerminalRow row = asciiRow(3, "abc", TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 3, -1, -1, -1, false, codePoint -> false);

        Assert.assertEquals(3, runs.size());
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(i, runs.get(i).getStartColumn());
            Assert.assertEquals(1, runs.get(i).getColumnWidth());
        }
    }

    @Test
    public void testWidthMismatch_splitsRuns() {
        TerminalRow row = asciiRow(3, "abc", TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 3, -1, -1, -1, true, codePoint -> codePoint == 'b');

        Assert.assertEquals(3, runs.size());
        Assert.assertEquals(1, runs.get(1).getStartColumn());
        Assert.assertEquals(1, runs.get(1).getColumnWidth());
    }

    @Test
    public void testWideCharCursorOnSecondHalf_marksInCursor() {
        TerminalRow row = new TerminalRow(4, TextStyle.NORMAL);
        row.setChar(0, 'a', TextStyle.NORMAL);
        row.setChar(1, 0x4E2D, TextStyle.NORMAL);
        row.setChar(3, 'd', TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 4, 2, -1, -1, true, codePoint -> false);

        Assert.assertEquals(3, runs.size());
        Assert.assertFalse(runs.get(0).getInCursor());
        Assert.assertTrue(runs.get(1).getInCursor());
        Assert.assertEquals(1, runs.get(1).getStartColumn());
        Assert.assertEquals(2, runs.get(1).getColumnWidth());
        Assert.assertFalse(runs.get(2).getInCursor());
        Assert.assertEquals(3, runs.get(2).getStartColumn());
    }

    @Test
    public void testCombiningChar_absorbedInBaseRun() {
        TerminalRow row = new TerminalRow(4, TextStyle.NORMAL);
        row.setChar(0, 'e', TextStyle.NORMAL);
        row.setChar(0, 0x0301, TextStyle.NORMAL);

        List<ComposeTerminalFrame.TextRun> runs =
            ComposeTerminalFrame.buildLineRuns(row, 4, -1, -1, -1, true, codePoint -> false);

        Assert.assertEquals(1, runs.size());
        Assert.assertEquals(4, runs.get(0).getColumnWidth());
        Assert.assertEquals(5, runs.get(0).getCharCount());
    }

    @Test
    public void testRuns_alwaysFormValidTextSlices() {
        // Contract consumed by Paint.measureText/drawTextRun (index, count): every run must
        // satisfy start >= 0, count >= 0 and start + count <= mText.length, otherwise the
        // canvas crashes with ArrayIndexOutOfBoundsException on the first styled prompt.
        TerminalRow[] rows = new TerminalRow[] {
            asciiRow(8, "ls --col", TextStyle.NORMAL),
            asciiRow(3, "abc", TextStyle.NORMAL),
            new TerminalRow(4, TextStyle.NORMAL)
        };
        rows[2].setChar(0, 'e', TextStyle.NORMAL);
        rows[2].setChar(0, 0x0301, TextStyle.NORMAL);
        rows[2].setChar(1, 0x4E2D, TextStyle.NORMAL);

        long bold = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD);
        TerminalRow styled = new TerminalRow(6, TextStyle.NORMAL);
        for (int col = 0; col < 6; col++) {
            styled.setChar(col, 'a' + col, col < 3 ? TextStyle.NORMAL : bold);
        }

        assertValidSlices(rows[0], 8, -1, -1, -1, true);
        assertValidSlices(rows[1], 3, 1, 0, 2, false);
        assertValidSlices(rows[2], 4, 2, -1, -1, true);
        assertValidSlices(styled, 6, 4, -1, -1, true);
        assertValidSlices(styled, 6, -1, 1, 4, true);
    }

    private static void assertValidSlices(TerminalRow row, int columns, int cursorX,
                                          int selX1, int selX2, boolean ligatures) {
        List<ComposeTerminalFrame.TextRun> runs = ComposeTerminalFrame.buildLineRuns(
            row, columns, cursorX, selX1, selX2, ligatures, codePoint -> codePoint == 'X');
        Assert.assertFalse(runs.isEmpty());
        for (ComposeTerminalFrame.TextRun run : runs) {
            Assert.assertTrue("start >= 0", run.getStartCharIndex() >= 0);
            Assert.assertTrue("count >= 0", run.getCharCount() >= 0);
            Assert.assertTrue("start + count <= mText.length",
                run.getStartCharIndex() + run.getCharCount() <= row.mText.length);
            Assert.assertTrue("column width >= 0", run.getColumnWidth() >= 0);
        }
    }

    @Test
    public void testClampScrollOffset() {
        Assert.assertEquals(0, ComposeTerminalFrame.clampScrollOffset(0, 100));
        Assert.assertEquals(0, ComposeTerminalFrame.clampScrollOffset(5, 100));
        Assert.assertEquals(-3, ComposeTerminalFrame.clampScrollOffset(-3, 100));
        Assert.assertEquals(-100, ComposeTerminalFrame.clampScrollOffset(-500, 100));
        Assert.assertEquals(0, ComposeTerminalFrame.clampScrollOffset(-5, 0));
        Assert.assertEquals(0, ComposeTerminalFrame.clampScrollOffset(1, 0));
    }

    @Test
    public void testAccumulateDragRows() {
        // Exact rows, positive (finger dragged down).
        kotlin.Pair<Integer, Float> down =
            ComposeTerminalFrame.accumulateDragRows(0f, 40f, 20);
        Assert.assertEquals(2, (int) down.getFirst());
        Assert.assertEquals(0f, down.getSecond(), 0.001f);

        // Exact rows, negative (finger dragged up).
        kotlin.Pair<Integer, Float> up =
            ComposeTerminalFrame.accumulateDragRows(0f, -40f, 20);
        Assert.assertEquals(-2, (int) up.getFirst());
        Assert.assertEquals(0f, up.getSecond(), 0.001f);

        // Fractional remainder carries to the next event.
        kotlin.Pair<Integer, Float> partial =
            ComposeTerminalFrame.accumulateDragRows(0f, 15f, 20);
        Assert.assertEquals(0, (int) partial.getFirst());
        Assert.assertEquals(15f, partial.getSecond(), 0.001f);
        kotlin.Pair<Integer, Float> carried =
            ComposeTerminalFrame.accumulateDragRows(partial.getSecond(), 10f, 20);
        Assert.assertEquals(1, (int) carried.getFirst());
        Assert.assertEquals(5f, carried.getSecond(), 0.001f);

        // Truncation toward zero (legacy `(int) (pixels / lineSpacing)` cast, see
        // TerminalView.onScroll): an up-drag below one row folds to 0 rows and carries
        // a negative remainder, so 2x15px up-drags sum to one row like the legacy view.
        kotlin.Pair<Integer, Float> upPartial =
            ComposeTerminalFrame.accumulateDragRows(0f, -15f, 20);
        Assert.assertEquals(0, (int) upPartial.getFirst());
        Assert.assertEquals(-15f, upPartial.getSecond(), 0.001f);
        kotlin.Pair<Integer, Float> upWhole =
            ComposeTerminalFrame.accumulateDragRows(-15f, -16f, 20);
        Assert.assertEquals(-1, (int) upWhole.getFirst());
        Assert.assertEquals(-11f, upWhole.getSecond(), 0.001f);

        // Degenerate spacing never moves.
        kotlin.Pair<Integer, Float> degenerate =
            ComposeTerminalFrame.accumulateDragRows(7f, 100f, 0);
        Assert.assertEquals(0, (int) degenerate.getFirst());
        Assert.assertEquals(7f, degenerate.getSecond(), 0.001f);
    }

    @Test
    public void testResolve_boldMapsToBright() {
        int[] palette = distinctPalette();
        long bold = TextStyle.encode(1, TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.CHARACTER_ATTRIBUTE_BOLD);

        ComposeTerminalFrame.ResolvedRunColors resolved = ComposeTerminalFrame.resolveRunColors(
            bold, palette, palette[TextStyle.COLOR_INDEX_BACKGROUND], false, false, false,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);

        Assert.assertEquals(palette[9], resolved.getForeColor());
        Assert.assertFalse(resolved.getDrawBackground());
        Assert.assertEquals(0, resolved.getCursorColor());
        Assert.assertTrue(resolved.getDrawText());
    }

    @Test
    public void testResolve_inverseSwaps() {
        int[] palette = distinctPalette();
        long inverse = TextStyle.encode(2, 3, TextStyle.CHARACTER_ATTRIBUTE_INVERSE);

        ComposeTerminalFrame.ResolvedRunColors resolved = ComposeTerminalFrame.resolveRunColors(
            inverse, palette, palette[TextStyle.COLOR_INDEX_BACKGROUND], false, false, false,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);

        Assert.assertEquals(palette[3], resolved.getForeColor());
        Assert.assertEquals(palette[2], resolved.getBackColor());
        Assert.assertTrue(resolved.getDrawBackground());
    }

    @Test
    public void testResolve_invisibleSkipsText() {
        int[] palette = distinctPalette();
        long invisible = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND,
            TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE);

        ComposeTerminalFrame.ResolvedRunColors resolved = ComposeTerminalFrame.resolveRunColors(
            invisible, palette, palette[TextStyle.COLOR_INDEX_BACKGROUND], false, false, false,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);

        Assert.assertFalse(resolved.getDrawText());
    }

    @Test
    public void testResolve_blockCursorInverts() {
        int[] palette = distinctPalette();
        long plain = TextStyle.encode(2, 3, 0);

        ComposeTerminalFrame.ResolvedRunColors resolved = ComposeTerminalFrame.resolveRunColors(
            plain, palette, palette[TextStyle.COLOR_INDEX_BACKGROUND], false, false, true,
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK);

        Assert.assertEquals(palette[3], resolved.getForeColor());
        Assert.assertEquals(palette[2], resolved.getBackColor());
        Assert.assertEquals(palette[TextStyle.COLOR_INDEX_CURSOR], resolved.getCursorColor());
    }

    @Test
    public void testSelectionBoundsForRow() {
        ComposeTerminalFrame.TextSelection selection =
            new ComposeTerminalFrame.TextSelection(3, -2, 7, 0);

        assertBounds(-1, -1, ComposeTerminalFrame.selectionBoundsForRow(null, 0, 12));
        assertBounds(-1, -1, ComposeTerminalFrame.selectionBoundsForRow(selection, -3, 12));
        assertBounds(-1, -1, ComposeTerminalFrame.selectionBoundsForRow(selection, 1, 12));
        // First selected row runs from x1 to the line end, last from 0 to x2, middle whole.
        assertBounds(3, 11, ComposeTerminalFrame.selectionBoundsForRow(selection, -2, 12));
        assertBounds(0, 11, ComposeTerminalFrame.selectionBoundsForRow(selection, -1, 12));
        assertBounds(0, 7, ComposeTerminalFrame.selectionBoundsForRow(selection, 0, 12));
    }

    private static void assertBounds(int expectedX1, int expectedX2, kotlin.Pair<Integer, Integer> bounds) {
        Assert.assertEquals(expectedX1, (int) bounds.getFirst());
        Assert.assertEquals(expectedX2, (int) bounds.getSecond());
    }

    @Test
    public void testSelectWord() {
        TerminalBuffer screen = new TerminalBuffer(6, 6, 6);
        screen.setChar(0, 0, 'l', 0);
        screen.setChar(1, 0, 's', 0);

        // Whitespace cell is selected alone.
        ComposeTerminalFrame.TextSelection space =
            ComposeTerminalFrame.selectWord(screen, 5, 0, 6);
        Assert.assertEquals(5, space.getX1());
        Assert.assertEquals(5, space.getX2());
        // Non-space expands while neighbors read back non-empty; the trailing space
        // cells read back "" so the word stops at the used text.
        ComposeTerminalFrame.TextSelection word =
            ComposeTerminalFrame.selectWord(screen, 1, 0, 6);
        Assert.assertEquals(0, word.getX1());
        Assert.assertEquals(1, word.getX2());
    }

    @Test
    public void testValidCurXWideGlyph() {
        TerminalBuffer screen = new TerminalBuffer(6, 6, 6);
        screen.setChar(1, 0, 0x4E2D, 0);
        // Inside the second half of a wide glyph: snap past it.
        Assert.assertEquals(3, ComposeTerminalFrame.validCurX(screen, 0, 2, 6));
        // On the wide glyph itself: keep the column.
        Assert.assertEquals(1, ComposeTerminalFrame.validCurX(screen, 0, 1, 6));
    }

    @Test
    public void testClampSelectionHandle() {
        TerminalBuffer screen = new TerminalBuffer(6, 6, 6);
        ComposeTerminalFrame.TextSelection original =
            new ComposeTerminalFrame.TextSelection(1, 0, 4, 0);

        // Start handle dragged past the end handle clamps onto it.
        ComposeTerminalFrame.TextSelection start =
            ComposeTerminalFrame.clampSelectionHandle(6, 0, original, true, 6, 0, 6, screen);
        Assert.assertEquals(4, start.getX1());
        Assert.assertEquals(4, start.getX2());

        // End handle dragged before the start handle clamps onto it.
        ComposeTerminalFrame.TextSelection end =
            ComposeTerminalFrame.clampSelectionHandle(-1, 0, original, false, 6, 0, 6, screen);
        Assert.assertEquals(1, end.getX1());
        Assert.assertEquals(1, end.getX2());

        // Rows clamp into [-rowsInHistory, mRows - 1].
        ComposeTerminalFrame.TextSelection rows =
            ComposeTerminalFrame.clampSelectionHandle(1, -9, original, true, 6, 2, 6, screen);
        Assert.assertEquals(-2, rows.getY1());
        Assert.assertEquals(1, rows.getX1());
    }

    @Test
    public void testShiftSelectionForNewOutput() {
        ComposeTerminalFrame.TextSelection selection =
            new ComposeTerminalFrame.TextSelection(1, -2, 4, -1);

        // New output scrolls both the offset and the selection up.
        kotlin.Pair<Integer, ComposeTerminalFrame.TextSelection> shifted =
            ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 2, 10);
        Assert.assertEquals(-5, (int) shifted.getFirst());
        ComposeTerminalFrame.TextSelection shiftedSelection = shifted.getSecond();
        Assert.assertEquals(-4, shiftedSelection.getY1());
        Assert.assertEquals(-3, shiftedSelection.getY2());

        // End of history: abort the selection and snap the scroll to live.
        kotlin.Pair<Integer, ComposeTerminalFrame.TextSelection> aborted =
            ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 4, 5);
        Assert.assertEquals(0, (int) aborted.getFirst());
        Assert.assertNull(aborted.getSecond());

        // No shift or no selection is a no-op.
        Assert.assertEquals(-3,
            (int) ComposeTerminalFrame.shiftSelectionForNewOutput(selection, -3, 0, 10).getFirst());
        Assert.assertNull(ComposeTerminalFrame.shiftSelectionForNewOutput(null, -3, 2, 10).getSecond());
    }

    @Test
    public void testFlingBounds() {
        Assert.assertEquals(new kotlin.ranges.IntRange(-4, 4),
            ComposeTerminalFrame.flingBounds(-4, 4));
        Assert.assertEquals(new kotlin.ranges.IntRange(-10, 0),
            ComposeTerminalFrame.flingBounds(-10, 0));
    }

    @Test
    public void testGridSizeClampsToMinimumFour() {
        // Legacy Math.max(4, ...) parity: a tiny canvas never collapses below 4 columns/rows.
        kotlin.Pair<Integer, Integer> tiny = ComposeTerminalFrame.gridSize(10, 10, 20f, 20, 40);
        Assert.assertEquals(4, (int) tiny.getFirst());
        Assert.assertEquals(4, (int) tiny.getSecond());

        kotlin.Pair<Integer, Integer> empty = ComposeTerminalFrame.gridSize(0, 0, 20f, 20, 40);
        Assert.assertEquals(4, (int) empty.getFirst());
        Assert.assertEquals(4, (int) empty.getSecond());

        // Normal sizing truncates like TerminalView.updateSize (float width division,
        // integer height division).
        kotlin.Pair<Integer, Integer> normal = ComposeTerminalFrame.gridSize(2000, 1000, 10f, 20, 5);
        Assert.assertEquals(200, (int) normal.getFirst());
        Assert.assertEquals(49, (int) normal.getSecond());

        // Degenerate metrics never divide by zero.
        kotlin.Pair<Integer, Integer> degenerate = ComposeTerminalFrame.gridSize(500, 500, 0f, 0, 0);
        Assert.assertEquals(4, (int) degenerate.getFirst());
        Assert.assertEquals(4, (int) degenerate.getSecond());
    }

    @Test
    public void testScrollOffsetForNewOutput() {
        // Legacy onScreenUpdated: with auto-scroll enabled a scrolled-back view snaps to live
        // on the next screen update.
        Assert.assertEquals(0, ComposeTerminalFrame.scrollOffsetForNewOutput(-5, 2, 50, false));
        Assert.assertEquals(0, ComposeTerminalFrame.scrollOffsetForNewOutput(0, 0, 50, false));

        // Auto-scroll disabled keeps the detached position, shifted up by the new rows.
        Assert.assertEquals(-5, ComposeTerminalFrame.scrollOffsetForNewOutput(-3, 2, 50, true));
        // No new output keeps the offset untouched.
        Assert.assertEquals(-3, ComposeTerminalFrame.scrollOffsetForNewOutput(-3, 0, 50, true));
        // Hitting the transcript end pins to the oldest row.
        Assert.assertEquals(-50, ComposeTerminalFrame.scrollOffsetForNewOutput(-50, 3, 50, true));
        Assert.assertEquals(-50, ComposeTerminalFrame.scrollOffsetForNewOutput(-60, 3, 50, true));
    }
}
