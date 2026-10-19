package com.termux.terminal;

/**
 * One on-screen placement of an already transmitted image: which payload it shows, the cell
 * rectangle it occupies, its source crop in image pixels and its stacking order (kitty
 * graphics {@code a=p}, plan §2.1).
 * <p>
 * The same image can be placed several times (one payload, N placements), so the payload
 * bytes live in {@link TerminalImageData} and this class only carries geometry and
 * references (RF-36, RNF-7). Cells reference a placement through its {@link #key} — see
 * {@link TerminalRow#getImage(int)}.
 * <p>
 * Android-free and read by both renderers, which is why the fields are public and the
 * scaling maths itself lives in the shared pure helpers of {@link KittyPlacementGeometry}.
 */
public final class TerminalImagePlacement {

    /**
     * Stable 1-based identity of this placement, never reused. Cells store this value;
     * {@link TerminalEmulator#getPlacement(int)} resolves it (null when the placement was
     * discarded, in which case the cell reservation is stale and must be ignored).
     */
    public final int key;

    /** 1-based registry index of the shared payload in {@link TerminalEmulator#getImageData(int)}. */
    public final int registryId;

    /** Client placement id ({@code p=} of the {@code a=p} command), or {@code 0} for an anonymous one. */
    public final int placementId;

    /** Kitty {@code z=}: negative paints below the text glyphs (RF-18). */
    public final int z;

    /** Monotonic emulator creation counter, used as the {@code z} tiebreak (RF-20) and as the age for eviction (RF-37); never a clock. */
    public final long creationSeq;

    /** External (transcript-aware) row of the top-left cell; moves with scroll (RF-30). */
    public int startRow;

    /** Screen column of the top-left cell. */
    public int startCol;

    /** Destination rectangle width in cells. */
    public int cellsW;

    /** Destination rectangle height in cells. */
    public int cellsH;

    /** Source crop origin X in image pixels (0 = whole image, until RF-11 is implemented). */
    public int cropX;

    /** Source crop origin Y in image pixels (0 = whole image, until RF-11 is implemented). */
    public int cropY;

    /** Source crop width in image pixels; 0 means "the whole image" (until RF-11 is implemented). */
    public int cropW;

    /** Source crop height in image pixels; 0 means "the whole image" (until RF-11 is implemented). */
    public int cropH;

    /** Whether the placement was stamped into the alternate screen buffer (RF-32). */
    public boolean onAltScreen;

    /**
     * @param key         stable 1-based placement key, never reused
     * @param registryId  1-based registry index of the referenced payload
     * @param placementId client {@code p=} value, or 0 for an anonymous placement
     * @param startRow    external row of the top-left cell
     * @param startCol    column of the top-left cell
     * @param cellsW      destination width in cells
     * @param cellsH      destination height in cells
     * @param cropX       source crop origin X in image pixels
     * @param cropY       source crop origin Y in image pixels
     * @param cropW       source crop width in image pixels, or 0 for the whole image
     * @param cropH       source crop height in image pixels, or 0 for the whole image
     * @param z           kitty {@code z=} (negative paints below the glyphs)
     * @param creationSeq monotonic creation counter of the emulator
     * @param onAltScreen {@code true} when stamped into the alternate screen buffer
     */
    public TerminalImagePlacement(int key, int registryId, int placementId,
                                  int startRow, int startCol, int cellsW, int cellsH,
                                  int cropX, int cropY, int cropW, int cropH,
                                  int z, long creationSeq, boolean onAltScreen) {
        this.key = key;
        this.registryId = registryId;
        this.placementId = placementId;
        this.startRow = startRow;
        this.startCol = startCol;
        this.cellsW = cellsW;
        this.cellsH = cellsH;
        this.cropX = cropX;
        this.cropY = cropY;
        this.cropW = cropW;
        this.cropH = cropH;
        this.z = z;
        this.creationSeq = creationSeq;
        this.onAltScreen = onAltScreen;
    }

    /**
     * Whether this placement still reserves and paints cells. A placement may have scrolled
     * partway into the transcript (negative {@link #startRow}) and is still live there, so
     * only the cell dimensions gate this.
     *
     * @return {@code true} when the rectangle has at least one cell
     */
    public boolean isVisible() {
        return cellsW > 0 && cellsH > 0;
    }

    /**
     * Whether an external row intersects this placement's vertical span.
     *
     * @param externalRow the row to test
     * @return {@code true} when the row is inside the placement
     */
    public boolean intersectsRow(int externalRow) {
        return isVisible() && externalRow >= startRow && externalRow - (long) startRow < cellsH;
    }

    /**
     * Whether a screen cell falls inside this placement's rectangle.
     *
     * @param externalRow external (transcript-aware) row
     * @param column      screen column
     * @return {@code true} when the cell is covered
     */
    public boolean containsCell(int externalRow, int column) {
        return KittyPlacementGeometry.coversCell(startRow, startCol, cellsW, cellsH, externalRow, column);
    }
}
