package com.termux.terminal;

import java.util.List;

/**
 * Pure geometry of kitty image placements: source crop resolution, destination cell
 * counts, clipping to the visible area, per-cell source pixel bands, stacking order and
 * cell coverage.
 * <p>
 * Stateless, Android-free and deterministic: every input (including the creation sequence
 * used by RF-20) is a parameter, so the emulator and both renderers share one single
 * implementation of the scaling maths (RNF-2, RNF-4, RNF-5). See plan §3.
 */
public final class KittyPlacementGeometry {

    /** Static helpers only. */
    private KittyPlacementGeometry() {
    }

    /**
     * Resolve the source crop in image pixels, clipped to the image (RF-12..RF-15).
     * <p>
     * A non-positive {@code cropW}/{@code cropH} means "rest of the image from the origin"
     * (RF-12) and a negative {@code cropX}/{@code cropY} is treated as 0 (RF-13). The
     * result never extends beyond the image (RF-14); when the intersection is empty the
     * requested origin is kept but the size is 0, which is what makes the placement
     * invisible later (RF-15). An unknown image size
     * (0) can host no pixels at all, so the width and height are 0 too.
     *
     * @param imageWidth  known image width in pixels, or 0 when unknown
     * @param imageHeight known image height in pixels, or 0 when unknown
     * @param cropX       requested crop origin X in pixels; negative means 0
     * @param cropY       requested crop origin Y in pixels; negative means 0
     * @param cropW       requested crop width in pixels; {@code <= 0} means up to the right edge
     * @param cropH       requested crop height in pixels; {@code <= 0} means up to the bottom edge
     * @return {@code {left, top, width, height}} with width/height 0 when nothing is visible
     */
    public static int[] resolveSourceCrop(int imageWidth, int imageHeight,
                                          int cropX, int cropY, int cropW, int cropH) {
        final int maxX = Math.max(0, imageWidth);
        final int maxY = Math.max(0, imageHeight);
        final int left = Math.max(0, cropX);
        final int top = Math.max(0, cropY);
        final int right = (cropW <= 0) ? maxX : (int) Math.min(maxX, left + (long) cropW);
        final int bottom = (cropH <= 0) ? maxY : (int) Math.min(maxY, top + (long) cropH);
        return new int[]{left, top, Math.max(0, right - left), Math.max(0, bottom - top)};
    }

    /**
     * Resolve the destination rectangle in cells (RF-7, RF-9, RF-10).
     * <p>
     * Two positive counts are used verbatim, whatever the aspect ratio says (RF-7). When
     * only one side is requested, the other is derived from the origin aspect ratio in
     * pixels, rounded up and never below 1 cell (RF-9), with the same arithmetic the
     * emulator used before this spec; a non-positive {@code cellPxW}/{@code cellPxH} means
     * "1 pixel = 1 cell" (cell metrics unknown), so nothing divides by zero. When both
     * counts are missing the origin pixels are used directly, and unknown pixels make the
     * placement invalid: {@code null} (RF-10). With one count missing and unknown pixels
     * there is no aspect to derive from, so the requested side is reused (minimum 1).
     *
     * @param requestedCols client {@code c=} in cells, or {@code <= 0} when absent
     * @param requestedRows client {@code r=} in cells, or {@code <= 0} when absent
     * @param srcPxW        source region width in pixels, or 0 when unknown
     * @param srcPxH        source region height in pixels, or 0 when unknown
     * @param cellPxW       cell width in pixels, or 0 when the host has not set it yet
     * @param cellPxH       cell height in pixels, or 0 when the host has not set it yet
     * @return {@code {cols, rows}} in cells, or {@code null} when the placement is invalid
     */
    public static int[] resolveCells(int requestedCols, int requestedRows,
                                     int srcPxW, int srcPxH, int cellPxW, int cellPxH) {
        if (requestedCols > 0 && requestedRows > 0) return new int[]{requestedCols, requestedRows};

        final int cellW = Math.max(1, cellPxW);
        final int cellH = Math.max(1, cellPxH);
        final boolean hasPixels = srcPxW > 0 && srcPxH > 0;

        if (requestedCols <= 0 && requestedRows <= 0) {
            if (!hasPixels) return null;
            return new int[]{ceilDiv(srcPxW, cellW), ceilDiv(srcPxH, cellH)};
        }
        if (requestedRows <= 0) {
            if (!hasPixels) return new int[]{Math.max(1, requestedCols), Math.max(1, requestedCols)};
            final int px = (int) Math.round((long) requestedCols * cellW * srcPxH / (double) srcPxW);
            return new int[]{requestedCols, Math.max(1, ceilDiv(px, cellH))};
        }
        if (!hasPixels) return new int[]{Math.max(1, requestedRows), Math.max(1, requestedRows)};
        final int px = (int) Math.round((long) requestedRows * cellH * srcPxW / (double) srcPxH);
        return new int[]{Math.max(1, ceilDiv(px, cellW)), requestedRows};
    }

    /**
     * Intersect a placement rectangle with the visible area, without rescaling it
     * (RF-8, RF-31).
     * <p>
     * The columns of the visible area always start at 0; the rows are addressed in external
     * (transcript-aware) coordinates, so {@code visibleTopRow} is negative while the content
     * is scrolled. A rectangle that only crosses the right or the bottom edge is clipped,
     * never scaled; one that is completely outside yields {@code null}.
     *
     * @param startRow       first row of the rectangle, external coordinates
     * @param startCol       first column of the rectangle
     * @param cellsW         rectangle width in cells
     * @param cellsH         rectangle height in cells
     * @param visibleTopRow  external row of the first visible row (negative when scrolled)
     * @param visibleRows    number of visible rows
     * @param visibleColumns number of visible columns
     * @return {@code {startRow, endRow, startCol, endCol}} with exclusive ends, or
     *         {@code null} when nothing of the rectangle is visible
     */
    public static int[] clipToVisibleArea(int startRow, int startCol, int cellsW, int cellsH,
                                          int visibleTopRow, int visibleRows, int visibleColumns) {
        if (cellsW <= 0 || cellsH <= 0 || visibleRows <= 0 || visibleColumns <= 0) return null;

        final int clippedRowStart = (int) Math.max(startRow, (long) visibleTopRow);
        final int clippedRowEnd = (int) Math.min(startRow + (long) cellsH, visibleTopRow + (long) visibleRows);
        if (clippedRowStart >= clippedRowEnd) return null;

        final int clippedColStart = Math.max(0, startCol);
        final int clippedColEnd = (int) Math.min(startCol + (long) cellsW, visibleColumns);
        if (clippedColStart >= clippedColEnd) return null;

        return new int[]{clippedRowStart, clippedRowEnd, clippedColStart, clippedColEnd};
    }

    /**
     * Source pixel band (inside the crop) that maps to one row and a run of columns of the
     * placement rectangle (RF-11, RF-14, RF-16, RF-17).
     * <p>
     * This is the single scaling formula of the whole feature: cells map to complete
     * pixels, edges aligned to the cell grid, with integer division and a minimum of 1 pixel
     * per axis. With a full-image crop ({@code cropX=0, cropY=0, cropW=bitmapWidth,
     * cropH=bitmapHeight}) and the placement's own cells it produces exactly the same
     * mapping the pre-placements image strip pass computed for the bitmap,
     * which is what keeps the single-placement (yazi) case pixel-identical (RF-35). Rows and
     * columns are clamped to the rectangle, so callers may pass a strip that overruns it.
     *
     * @param cropX     crop origin X in image pixels
     * @param cropY     crop origin Y in image pixels
     * @param cropW     crop width in image pixels
     * @param cropH     crop height in image pixels
     * @param cellsW    width of the placement rectangle in cells
     * @param cellsH    height of the placement rectangle in cells
     * @param localRow  row of the placement rectangle (0-based)
     * @param localCol  first column of the strip (0-based)
     * @param spanCells strip width in cells
     * @return {@code {left, top, right, bottom}} in image pixel coordinates, at least 1 pixel
     *         per axis and inside the crop, or {@code null} when the crop or the rectangle is
     *         empty (nothing to paint, see RF-15)
     */
    public static int[] sourcePixelBand(int cropX, int cropY, int cropW, int cropH,
                                        int cellsW, int cellsH,
                                        int localRow, int localCol, int spanCells) {
        if (cropW <= 0 || cropH <= 0 || cellsW <= 0 || cellsH <= 0) return null;

        final int row = clamp(localRow, 0, cellsH - 1);
        final int col = clamp(localCol, 0, cellsW - 1);
        final int span = clamp(spanCells, 1, cellsW - col);

        final int left = (int) (cropX + (long) col * cropW / cellsW);
        final int right = (int) (cropX + (long) (col + span) * cropW / cellsW);
        final int top = (int) (cropY + (long) row * cropH / cellsH);
        final int bottom = (int) (cropY + (long) (row + 1) * cropH / cellsH);
        return new int[]{left, top, Math.max(left + 1, right), Math.max(top + 1, bottom)};
    }

    /**
     * Whether a placement paints below the text glyphs (RF-18).
     *
     * @param z placement {@code z=}; absent means 0
     * @return {@code true} only when {@code z} is negative
     */
    public static boolean isBelowText(int z) {
        return z < 0;
    }

    /**
     * Paint order of two placements (RF-20): ascending {@code z}, and on equal {@code z} the
     * later creation wins, i.e. ends up on top.
     * <p>
     * Both the {@code z} scores and the creation sequences are compared with the
     * overflow-safe {@link Integer#compare}/{@link Long#compare} helpers, so neither very
     * negative {@code z} values nor sequences beyond 256 placements can wrap around.
     *
     * @param zA   {@code z=} of the first placement
     * @param seqA creation sequence of the first placement (monotonic counter, never a clock)
     * @param zB   {@code z=} of the second placement
     * @param seqB creation sequence of the second placement
     * @return negative when A paints first, positive when B paints first, 0 when equivalent
     */
    public static int comparePlacementOrder(int zA, long seqA, int zB, long seqB) {
        final int byZ = Integer.compare(zA, zB);
        if (byZ != 0) return byZ;
        return Long.compare(seqA, seqB);
    }

    /**
     * Whether a placement covers a screen cell, used both for the topmost-cell resolution
     * (RF-21) and for the {@code d=p} deletion by cell coordinates (RF-27).
     * <p>
     * The rectangle is half-open: the origin cell is covered, the first cell past the right
     * and bottom edges is not. Rectangle arithmetic uses {@code long} so huge {@code c=}/{@code r=}
     * values cannot wrap around into covering unrelated cells.
     *
     * @param startRow    first row of the rectangle, external coordinates
     * @param startCol    first column of the rectangle
     * @param cellsW      rectangle width in cells
     * @param cellsH      rectangle height in cells
     * @param externalRow row of the queried cell
     * @param column      column of the queried cell
     * @return {@code true} when the cell is inside the rectangle
     */
    public static boolean coversCell(int startRow, int startCol, int cellsW, int cellsH,
                                     int externalRow, int column) {
        if (cellsW <= 0 || cellsH <= 0) return false;
        return externalRow >= startRow && externalRow - (long) startRow < cellsH
            && column >= startCol && column - (long) startCol < cellsW;
    }

    /**
     * Key of the topmost placement covering a cell (RF-20, RF-21), or 0 when no placement
     * covers it.
     * <p>
     * "Topmost" is the greatest paint order, i.e. the placement {@link #comparePlacementOrder}
     * would paint last: ascending {@code z} and, on a tie, the later creation sequence.
     * {@code null} entries (discarded placements) and invisible ones are skipped, so the
     * caller may pass the emulator's placement table as is.
     *
     * @param placements placements to search; may contain {@code null} slots
     * @param externalRow row of the queried cell
     * @param column the queried column
     * @return the {@link TerminalImagePlacement#key} of the topmost covering placement, or 0
     */
    public static int topmostKeyAt(List<TerminalImagePlacement> placements, int externalRow, int column) {
        if (placements == null) return 0;
        TerminalImagePlacement topmost = null;
        for (int i = 0; i < placements.size(); i++) {
            final TerminalImagePlacement candidate = placements.get(i);
            if (candidate == null || !candidate.containsCell(externalRow, column)) continue;
            if (topmost == null
                || comparePlacementOrder(candidate.z, candidate.creationSeq,
                    topmost.z, topmost.creationSeq) > 0) {
                topmost = candidate;
            }
        }
        return (topmost == null) ? 0 : topmost.key;
    }

    /**
     * Index (in the given list) of the oldest placement of an image (RF-37), or -1 when the
     * image has no placement there.
     * <p>
     * "Oldest" is the smallest creation sequence, which is the one the per-image placement cap
     * evicts first. {@code null} entries are skipped; placements of other images are ignored.
     *
     * @param placements placements to search; may contain {@code null} slots
     * @param registryId 1-based registry index of the image
     * @return the list index of the oldest placement of that image, or -1
     */
    public static int oldestPlacementIndexForImage(List<TerminalImagePlacement> placements, int registryId) {
        if (placements == null) return -1;
        int oldestIndex = -1;
        long oldestSeq = Long.MAX_VALUE;
        for (int i = 0; i < placements.size(); i++) {
            final TerminalImagePlacement candidate = placements.get(i);
            if (candidate == null || candidate.registryId != registryId) continue;
            if (oldestIndex < 0 || candidate.creationSeq < oldestSeq) {
                oldestIndex = i;
                oldestSeq = candidate.creationSeq;
            }
        }
        return oldestIndex;
    }

    /** Integer ceiling division for a positive divisor. */
    private static int ceilDiv(int value, int divisor) {
        return (int) (((long) value + divisor - 1) / divisor);
    }

    /** Clamp {@code value} into {@code [min, max]}. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
