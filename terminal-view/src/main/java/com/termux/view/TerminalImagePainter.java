package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.termux.terminal.KittyPlacementGeometry;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalImageData;
import com.termux.terminal.TerminalImagePlacement;

import java.util.ArrayList;

/**
 * Shared painter of inline image placements (RF-8, RF-18, RF-19, RF-23, RNF-3, RNF-4).
 * <p>
 * One pass of one row at a time: the legacy {@link TerminalRenderer} and the Compose canvas of
 * the {@code app} module call the same {@link #drawRowPass} with their own callbacks, so both
 * renderers clip the same rectangle and scale the same source band, and RNF-4 holds by
 * construction instead of by discipline.
 * <p>
 * The painter decodes nothing and caches nothing (the bitmap already decoded and cached by the
 * renderer arrives through {@link ImageBitmapProvider}); the placement order comes from
 * {@link TerminalEmulator#collectPlacementsForRow(int, boolean, java.util.List)}, which fills a
 * list owned by this instance, so painting a frame allocates no list per row and pass (RNF-3).
 * <p>
 * This is the only place that calls {@link KittyPlacementGeometry#clipToVisibleArea} and
 * {@link KittyPlacementGeometry#sourcePixelBand} and draws the bitmap.
 */
public final class TerminalImagePainter {

    private static final String LOG_TAG = "TerminalImagePainter";

    /**
     * Effective background color of a screen cell (style + palette + selection/reverse video),
     * resolved by the renderer because only it knows the palette and the text styles.
     */
    public interface CellBackgroundResolver {
        /**
         * @param column screen column of the cell being painted
         * @return the effective background color as ARGB
         */
        int colorAt(int column);
    }

    /** Bitmap of an image payload, already decoded and cached by the renderer (RNF-3). */
    public interface ImageBitmapProvider {
        /**
         * @param data payload shared by the placements being painted
         * @return the bitmap, or {@code null} when the payload cannot be decoded
         */
        Bitmap bitmapFor(TerminalImageData data);
    }

    /** Reused per-pass list: painting must not allocate a list per row and pass (RNF-3). */
    private final ArrayList<TerminalImagePlacement> mRowPlacements = new ArrayList<>();
    /** Reused source rectangle of the strip being drawn. */
    private final Rect mSourceRect = new Rect();
    /** Reused destination rectangle of the strip being drawn. */
    private final RectF mDestinationRect = new RectF();

    /**
     * Paint the placements of one pass of one row: each placement is clipped to its visible
     * columns without rescaling (RF-8), every covered cell is blanked with its own effective
     * background so transparent image pixels show the background and never the glyph (RF-23),
     * and the matching source band is drawn scaled onto the strip.
     * <p>
     * The pass is painted for a single row, so the visible area used to clip is exactly that row:
     * a rectangle that overruns the grid keeps only the columns inside it and a placement that
     * scrolled partly out of the active area draws only its remaining rows (RF-8/RF-31). Callers
     * paint the {@code belowText=true} pass before the glyphs and the {@code belowText=false} pass
     * after them (RF-19).
     *
     * @param canvas      target canvas
     * @param emulator    emulator holding the placement table and the image registry
     * @param externalRow external (transcript-aware) row being painted
     * @param rowTop      top of the cell row in canvas Y
     * @param rowBottom   bottom of the cell row in canvas Y
     * @param cellWidth   width of one cell in canvas X
     * @param belowText   {@code true} for the pass painted below the text glyphs ({@code z<0})
     * @param backgrounds per-cell effective background resolver (never {@code null})
     * @param bitmaps     decoded bitmap provider (never {@code null})
     * @param paint       paint used to fill the cell backgrounds (never {@code null})
     */
    public void drawRowPass(Canvas canvas, TerminalEmulator emulator, int externalRow,
                            float rowTop, float rowBottom, float cellWidth,
                            boolean belowText,
                            CellBackgroundResolver backgrounds, ImageBitmapProvider bitmaps, Paint paint) {
        final int columns = emulator.mColumns;
        emulator.collectPlacementsForRow(externalRow, belowText, mRowPlacements);
        for (int i = 0; i < mRowPlacements.size(); i++) {
            final TerminalImagePlacement placement = mRowPlacements.get(i);
            final int[] rect = KittyPlacementGeometry.clipToVisibleArea(placement.startRow,
                placement.startCol, placement.cellsW, placement.cellsH, externalRow, 1, columns);
            if (rect == null) continue;
            final TerminalImageData data = emulator.getImageData(placement.registryId);
            if (data == null) continue;
            final Bitmap bitmap = bitmaps.bitmapFor(data);
            if (bitmap == null || bitmap.isRecycled()) continue;
            drawStrip(canvas, bitmap, placement, rect[2], rect[3], externalRow,
                rowTop, rowBottom, cellWidth, backgrounds, paint);
        }
    }

    /**
     * Draw the visible columns {@code [colStart, colEnd)} of a placement on one row.
     *
     * @param canvas      target canvas
     * @param bitmap      decoded image of the placement
     * @param placement   the placement owning the geometry (cells and source crop)
     * @param colStart    first visible column of the row
     * @param colEnd      first column past the visible part of the row
     * @param externalRow external row being painted
     * @param rowTop      top of the cell row in canvas Y
     * @param rowBottom   bottom of the cell row in canvas Y
     * @param cellWidth   width of one cell in canvas X
     * @param backgrounds per-cell effective background resolver
     * @param paint       paint used to fill the cell backgrounds
     */
    private void drawStrip(Canvas canvas, Bitmap bitmap, TerminalImagePlacement placement,
                           int colStart, int colEnd, int externalRow,
                           float rowTop, float rowBottom, float cellWidth,
                           CellBackgroundResolver backgrounds, Paint paint) {
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        // No crop at all is the whole-image sentinel (RF-16): the band maps the decoded bitmap,
        // which is what keeps the single-placement case pixel-identical (RF-35). A real crop is
        // in the pixels the protocol advertised; the band is clamped below to the decoded bitmap
        // in case they disagree (defensive clamp, plan §6.2).
        final boolean cropped = placement.cropW > 0 && placement.cropH > 0;
        final int[] band = KittyPlacementGeometry.sourcePixelBand(
            cropped ? placement.cropX : 0,
            cropped ? placement.cropY : 0,
            cropped ? placement.cropW : bitmapWidth,
            cropped ? placement.cropH : bitmapHeight,
            placement.cellsW, placement.cellsH,
            externalRow - placement.startRow, colStart - placement.startCol, colEnd - colStart);
        if (band == null) return;
        final int srcLeft = clamp(band[0], 0, bitmapWidth - 1);
        final int srcTop = clamp(band[1], 0, bitmapHeight - 1);
        mSourceRect.set(srcLeft, srcTop,
            clamp(band[2], srcLeft + 1, bitmapWidth), clamp(band[3], srcTop + 1, bitmapHeight));
        mDestinationRect.set(colStart * cellWidth, rowTop, colEnd * cellWidth, rowBottom);

        // Blank every covered cell with its own effective background before the bitmap: text
        // runs are drawn underneath and transparent image pixels must show the background,
        // never the leftover glyph (RF-23).
        for (int column = colStart; column < colEnd; column++) {
            paint.setColor(backgrounds.colorAt(column));
            final float cellLeft = column * cellWidth;
            canvas.drawRect(cellLeft, rowTop, cellLeft + cellWidth, rowBottom, paint);
        }
        canvas.drawBitmap(bitmap, mSourceRect, mDestinationRect, null);
    }

    /** Clamp {@code value} into {@code [min, max]}. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
