package com.termux.terminal;

/**
 * A kitty unicode-placeholder virtual placement
 * ({@code a=p,U=1,i=<id>,c=<cols>,r=<rows>}).
 *
 * <p>Virtual placements have no screen location: they only describe the grid
 * the image will be fit into. The actual display happens when the client emits
 * {@code U+10EEEE} placeholder text, which the renderers resolve through
 * {@link TerminalEmulator#resolveVirtualPlacement(int)}.</p>
 */
public final class KittyVirtualPlacement {

    /** Registry index in {@link TerminalEmulator}'s image table. */
    public final int registryId;
    /** Grid width in cells ({@code c=}). */
    public final int cols;
    /** Grid height in cells ({@code r=}). */
    public final int rows;

    /**
     * @param registryId registry index of the image data
     * @param cols       grid width in cells (&gt;= 1)
     * @param rows       grid height in cells (&gt;= 1)
     */
    public KittyVirtualPlacement(int registryId, int cols, int rows) {
        this.registryId = registryId;
        this.cols = cols;
        this.rows = rows;
    }
}
