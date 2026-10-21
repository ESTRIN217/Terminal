package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.util.LruCache;

import com.termux.terminal.KittyPlaceholderDecoder;
import com.termux.terminal.KittyPlacementGeometry;
import com.termux.terminal.KittyVirtualPlacement;
import com.termux.terminal.TerminalUrlScanner;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalImageData;
import com.termux.terminal.TerminalImagePlacement;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    /** Whether OpenType ligature shaping is enabled (fonts with ligature tables, e.g. Fira Code). */
    final boolean mEnableLigatures;
    /** Whether OSC 8 hyperlinks paint with an underline (tap-to-open lives in the view/client). */
    private boolean mHyperlinksEnabled = true;
    /** Whether inline images (OSC 1337 / kitty) are painted. */
    private boolean mImagesEnabled = true;
    /** Decoded bitmap cache keyed by registry id; entries dropped when the registry evicts. */
    private final LruCache<Integer, CachedImageBitmap> mImageBitmaps = new LruCache<>(16);
    /** Shared painter of placement strips; the Compose canvas paints with the same class (RNF-4). */
    private final TerminalImagePainter mImagePainter = new TerminalImagePainter();
    /** Bitmap provider handed to {@link #mImagePainter} (the cache above, identity checked). */
    private final TerminalImagePainter.ImageBitmapProvider mImageBitmapsProvider =
        new TerminalImagePainter.ImageBitmapProvider() {
            @Override
            public Bitmap bitmapFor(TerminalImageData data) {
                return TerminalRenderer.this.bitmapFor(data);
            }
        };
    /** Per-row cell background resolver handed to {@link #mImagePainter} (see {@link RowCellBackgrounds}). */
    private final RowCellBackgrounds mRowBackgrounds = new RowCellBackgrounds();
    private final Paint mTextPaint = new Paint();

    /** Per-row inheritance state for unicode-placeholder cells (reset per logical line). */
    private final KittyPlaceholderDecoder.RowState mPlaceholderState = new KittyPlaceholderDecoder.RowState();
    /** Scratch draw context for {@link #mPlaceholderVisitor} (avoids per-frame allocations). */
    private Canvas mPlaceholderCanvas;
    private TerminalEmulator mPlaceholderEmulator;
    private float mPlaceholderTop;
    private float mPlaceholderBottom;
    /** Scratch style/selection context for {@link #mPlaceholderVisitor} (cell blanking). */
    private TerminalRow mPlaceholderLine;
    private int[] mPlaceholderPalette;
    private boolean mPlaceholderReverseVideo;
    private int mPlaceholderSelX1;
    private int mPlaceholderSelX2;
    /** Placeholder columns without a target on the row being painted (spec 002, RF-1/RF-4). */
    private int[] mUnresolvedColumns = new int[0];
    /** Number of used entries in {@link #mUnresolvedColumns} (holder for the row lambda). */
    private final int[] mUnresolvedCount = new int[1];
    /** Paints decoded placeholder cells; runs after text so opaque images cover the glyph. */
    private final KittyPlaceholderDecoder.CellVisitor mPlaceholderVisitor =
        new KittyPlaceholderDecoder.CellVisitor() {
            @Override
            public void visit(int column, KittyPlaceholderDecoder.Target target) {
                drawPlaceholderCell(mPlaceholderCanvas, mPlaceholderEmulator, column, target,
                    mPlaceholderTop, mPlaceholderBottom);
            }
        };

    /**
     * Cached decode bound to the {@link TerminalImageData} instance it was decoded
     * from. The registry can re-transmit under the same id (payload replaced with a
     * new object); identity check makes the stale bitmap miss instead of showing
     * the old pixels.
     */
    private static final class CachedImageBitmap {
        final TerminalImageData source;
        final Bitmap bitmap;

        CachedImageBitmap(TerminalImageData source, Bitmap bitmap) {
            this.source = source;
            this.bitmap = bitmap;
        }
    }

    /**
     * Effective background of one cell of the row being painted, with the same colour logic
     * {@link #drawTextRun} uses for a text run (palette lookup and selection/reverse swap), so
     * the painter blanks each covered cell exactly like the glyph pass would (RF-23).
     * <p>
     * One instance per renderer, repointed at every row before the image passes run, so painting
     * allocates no resolver per row.
     */
    private final class RowCellBackgrounds implements TerminalImagePainter.CellBackgroundResolver {
        private TerminalRow mLine;
        private int[] mPalette;
        private boolean mReverseVideo;
        private int mSelX1 = -1;
        private int mSelX2 = -1;

        /**
         * Point the resolver at the row about to be painted.
         *
         * @param line         row being painted (per-cell styles)
         * @param palette      the emulator indexed colours
         * @param reverseVideo whether the emulator is in reverse-video mode
         * @param selX1        selection left bound for the row, or -1
         * @param selX2        selection right bound for the row, or -1
         */
        void setRow(TerminalRow line, int[] palette, boolean reverseVideo, int selX1, int selX2) {
            mLine = line;
            mPalette = palette;
            mReverseVideo = reverseVideo;
            mSelX1 = selX1;
            mSelX2 = selX2;
        }

        @Override
        public int colorAt(int column) {
            if (mLine == null) return 0;
            final boolean reverseHere = mReverseVideo || (column >= mSelX1 && column <= mSelX2);
            return TextStyle.effectiveBackgroundColor(mLine.getStyle(column), mPalette, reverseHere);
        }
    }

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /**
     * Scanner of plain-text URL spans over the logical lines (Spec 003, RF-3), shared with
     * {@link TerminalView}'s tap path (package-private access). Entries are cached per content
     * fingerprint (RNF-1) and both consumers run on the UI thread, matching the scanner's
     * no-locking contract.
     */
    final TerminalUrlScanner mUrlScanner = new TerminalUrlScanner();

    public TerminalRenderer(int textSize, Typeface typeface, boolean enableLigatures) {
        mTextSize = textSize;
        mTypeface = typeface;
        mEnableLigatures = enableLigatures;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /**
     * Enable or disable OSC 8 hyperlink underlines. Call when the
     * {@code terminal_hyperlinks} preference changes.
     *
     * @param enabled whether hyperlinks should be underlined
     */
    public void setHyperlinksEnabled(boolean enabled) {
        mHyperlinksEnabled = enabled;
    }

    /**
     * Enable or disable inline image painting. Call when the
     * {@code terminal_images} preference changes; also drops the bitmap cache.
     *
     * @param enabled whether inline images should be drawn
     */
    public void setImagesEnabled(boolean enabled) {
        mImagesEnabled = enabled;
        if (!enabled) mImageBitmaps.evictAll();
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        if (mImagesEnabled && mUnresolvedColumns.length < columns) mUnresolvedColumns = new int[columns];

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            java.util.HashSet<Integer> skipCells = null;
            java.util.HashSet<Integer> skipBackgroundCells = null;
            java.util.ArrayList<int[]> pendingPlaceholders = null;
            if (mImagesEnabled) {
                skipCells = new java.util.HashSet<>();
                skipBackgroundCells = new java.util.HashSet<>();
                pendingPlaceholders = new java.util.ArrayList<>();
                // The placement holding the cell decides what the glyph pass does with it
                // (RF-21): z>=0 covers the cell, so neither glyph nor background may be painted
                // (the image pass paints both); z<0 stays under the glyph, so only the cell
                // background must be left alone for the image to show through (RF-18/RF-23).
                for (int col = 0; col < columns; col++) {
                    final TerminalImagePlacement placement = mEmulator.getPlacementAt(row, col);
                    if (placement == null) continue;
                    if (KittyPlacementGeometry.isBelowText(placement.z)) skipBackgroundCells.add(col);
                    else skipCells.add(col);
                }
                if (!(row > topRow && screen.getLineWrap(row - 1))) mPlaceholderState.reset();
                final java.util.HashSet<Integer> finalSkipCells = skipCells;
                final java.util.ArrayList<int[]> finalPending = pendingPlaceholders;
                mUnresolvedCount[0] = 0;
                KittyPlaceholderDecoder.collectRow(lineObject, columns, mPlaceholderState, (col, target) -> {
                    finalSkipCells.add(col);
                    finalPending.add(new int[]{col, target.imageId, target.gridRow, target.gridCol});
                }, col -> {
                    finalSkipCells.add(col);
                    mUnresolvedColumns[mUnresolvedCount[0]++] = col;
                });
                mRowBackgrounds.setRow(lineObject, palette, reverseVideo, selx1, selx2);
                // First image pass: placements below the glyphs (RF-19).
                mImagePainter.drawRowPass(canvas, mEmulator, row, heightOffset - mFontLineSpacing,
                    heightOffset, mFontWidth, true, mRowBackgrounds, mImageBitmapsProvider, mTextPaint);
            }

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunHyperlink = 0;
            boolean lastRunInsideUrl = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            boolean lastRunSkipBackground = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            // Plain-text URL spans of this row (Spec 003, RF-3): flattened to a boolean over
            // the columns from the scanner's cached entry, allocated only when the row
            // carries spans at all (most rows have none; RNF-1 keeps the cost to a hash).
            boolean[] insideUrl = null;
            if (mHyperlinksEnabled) {
                TerminalUrlScanner.LogicalLineEntry entry = mUrlScanner.entryFor(screen, row);
                if (entry != null && entry.mSpans.length > 0) {
                    insideUrl = new boolean[columns];
                    for (TerminalUrlScanner.LinkSpan span : entry.mSpans) {
                        int[] range = entry.columnRangeForRow(screen, row, span);
                        if (range == null) continue;
                        for (int col = range[0]; col < range[1]; col++) insideUrl[col] = true;
                    }
                }
            }

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);
                final int hyperlink = lineObject.getHyperlink(column);
                // Both halves of a wide glyph carry the same flag, so a span never splits a
                // wide glyph's run (the scanner's ranges are cell complete).
                final boolean insideUrlHere = insideUrl != null && insideUrl[column];
                // A cell under a z<0 placement keeps its glyph but not its background: the image
                // pass already painted cell background + image, and repainting the background
                // here would hide the image (RF-18/RF-23).
                final boolean skipBackground = skipBackgroundCells != null && skipBackgroundCells.contains(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (skipCells != null && skipCells.contains(column)) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                            lastRunHyperlink != 0 && mHyperlinksEnabled || lastRunInsideUrl, !lastRunSkipBackground);
                    }
                    lastRunStartColumn = -1;
                    measuredWidthForRun = 0.f;
                    column += codePointWcWidth;
                    currentCharIndex += charsForCodePoint;
                    while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                        currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                    }
                    continue;
                }

                // Break the run when style, cursor, selection or OSC 8 hyperlink changes, when this code point
                // (or the one that started the run) has a width that does not match wcwidth(), when the cell
                // background must be left to the image underneath, when a new run must start because the
                // previous cells were skipped (no live run open), or
                // when ligatures are disabled, in which case every code point must be shaped alone
                // so that the font's ligature tables (GSUB liga/clig) can never combine characters.
                if (lastRunStartColumn < 0 || style != lastRunStyle || insideCursor != lastRunInsideCursor
                    || insideSelection != lastRunInsideSelection
                    || hyperlink != lastRunHyperlink || insideUrlHere != lastRunInsideUrl
                    || fontWidthMismatch || lastRunFontWidthMismatch || !mEnableLigatures
                    || skipBackground != lastRunSkipBackground) {
                    if (lastRunStartColumn >= 0) {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                            lastRunHyperlink != 0 && mHyperlinksEnabled || lastRunInsideUrl, !lastRunSkipBackground);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunHyperlink = hyperlink;
                    lastRunInsideUrl = insideUrlHere;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                    lastRunSkipBackground = skipBackground;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            if (lastRunStartColumn >= 0) {
                final int columnWidthSinceLastRun = columns - lastRunStartColumn;
                final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                boolean invertCursorTextColor = false;
                if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                    invertCursorTextColor = true;
                }
                drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                    measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                    lastRunHyperlink != 0 && mHyperlinksEnabled || lastRunInsideUrl, !lastRunSkipBackground);
            }
            if (mImagesEnabled) {
                // Second image pass: placements with z>=0 paint after the glyphs, so previews
                // sit above leftover cell glyphs (RF-19). The cursor was already painted inside
                // the runs above and stays on top of the image only when it falls outside the
                // image rect (acceptable v1).
                mImagePainter.drawRowPass(canvas, mEmulator, row, heightOffset - mFontLineSpacing,
                    heightOffset, mFontWidth, false, mRowBackgrounds, mImageBitmapsProvider, mTextPaint);
            }
            // Unicode placeholders (kitty U+10EEEE): paint after text so the image
            // covers the placeholder glyph; cells were collected before text to skip
            // the glyph in the text run.
            if (pendingPlaceholders != null) {
                mPlaceholderCanvas = canvas;
                mPlaceholderEmulator = mEmulator;
                mPlaceholderTop = heightOffset - mFontLineSpacing;
                mPlaceholderBottom = heightOffset;
                mPlaceholderLine = lineObject;
                mPlaceholderPalette = palette;
                mPlaceholderReverseVideo = reverseVideo;
                mPlaceholderSelX1 = selx1;
                mPlaceholderSelX2 = selx2;
                for (int[] ph : pendingPlaceholders) {
                    mPlaceholderVisitor.visit(ph[0],
                        new KittyPlaceholderDecoder.Target(ph[1], ph[2], ph[3]));
                }
                // Cells that are placeholders but have no target: blank them too (RF-1/RF-4).
                // The scratch context above is already pointing at this row's line/palette.
                for (int i = 0; i < mUnresolvedCount[0]; i++) {
                    blankPlaceholderCell(canvas, mUnresolvedColumns[i],
                        heightOffset - mFontLineSpacing, heightOffset);
                }
            }
        }
    }

    /**
     * Paint one unicode-placeholder cell as its grid slice of the virtual placement, or as
     * its effective cell background when the target cannot be painted. Kitty never draws the
     * placeholder glyph, so the cell is always blanked first (spec 002, RF-1/RF-3/RF-4).
     *
     * @param canvas   target
     * @param emulator the emulator (virtual placements + image registry)
     * @param column   screen column of the cell
     * @param target   decoded image id + grid position
     * @param top      cell top in canvas Y
     * @param bottom   cell bottom (baseline convention shared with text runs)
     */
    private void drawPlaceholderCell(Canvas canvas, TerminalEmulator emulator, int column,
                                     KittyPlaceholderDecoder.Target target, float top, float bottom) {
        blankPlaceholderCell(canvas, column, top, bottom);
        final KittyVirtualPlacement vp = emulator.resolveVirtualPlacement(target.imageId);
        if (vp == null) return;
        if (target.gridRow < 0 || target.gridRow >= vp.rows
            || target.gridCol < 0 || target.gridCol >= vp.cols) return;
        final TerminalImageData data = emulator.getImageData(vp.registryId);
        final Bitmap bitmap = bitmapFor(data);
        if (bitmap == null || bitmap.isRecycled()) return;
        final int bmpW = bitmap.getWidth();
        final int bmpH = bitmap.getHeight();
        final int srcTop = (int) ((long) target.gridRow * bmpH / vp.rows);
        final int srcBottom = (int) ((long) (target.gridRow + 1) * bmpH / vp.rows);
        final int srcLeft = (int) ((long) target.gridCol * bmpW / vp.cols);
        final int srcRight = (int) ((long) (target.gridCol + 1) * bmpW / vp.cols);
        final float left = column * mFontWidth;
        final float right = left + mFontWidth;
        final android.graphics.Rect src = new android.graphics.Rect(
            Math.min(srcLeft, bmpW - 1), Math.min(srcTop, bmpH - 1),
            Math.max(srcLeft + 1, Math.min(srcRight, bmpW)),
            Math.max(srcTop + 1, Math.min(srcBottom, bmpH)));
        final android.graphics.RectF dst = new android.graphics.RectF(left, top, right, bottom);
        canvas.drawBitmap(bitmap, src, dst, null);
    }

    /**
     * Blank one placeholder cell with its effective background (palette, reverse video,
     * selection and client SGR), so neither the placeholder glyph nor stale pixels from a
     * previous frame can show. Uses the scratch row context set before the placeholder pass.
     *
     * @param canvas target
     * @param column screen column of the cell
     * @param top    cell top in canvas Y
     * @param bottom cell bottom (baseline convention shared with text runs)
     */
    private void blankPlaceholderCell(Canvas canvas, int column, float top, float bottom) {
        final boolean reverseHere = mPlaceholderReverseVideo
            || (column >= mPlaceholderSelX1 && column <= mPlaceholderSelX2);
        mTextPaint.setColor(TextStyle.effectiveBackgroundColor(
            mPlaceholderLine.getStyle(column), mPlaceholderPalette, reverseHere));
        final float left = column * mFontWidth;
        canvas.drawRect(left, top, left + mFontWidth, bottom, mTextPaint);
    }

    /**
     * Decode (once) and cache the bitmap for a registry entry.
     *
     * @param data the image entry
     * @return the bitmap, or {@code null} when decode fails
     */
    private Bitmap bitmapFor(TerminalImageData data) {
        if (data == null || data.encoded == null || data.encoded.length == 0) return null;
        final CachedImageBitmap cached = mImageBitmaps.get(data.id);
        if (cached != null && cached.source == data && !cached.bitmap.isRecycled()) return cached.bitmap;
        Bitmap decoded = null;
        try {
            if (data.pixelFormat == TerminalImageData.FORMAT_RGB_24
                || data.pixelFormat == TerminalImageData.FORMAT_RGBA_32) {
                final int[] argb = data.decodeRawArgb();
                if (argb != null && data.pixelWidth > 0 && data.pixelHeight > 0)
                    decoded = Bitmap.createBitmap(argb, data.pixelWidth, data.pixelHeight, Bitmap.Config.ARGB_8888);
            } else {
                decoded = BitmapFactory.decodeByteArray(data.encoded, 0, data.encoded.length);
            }
        } catch (OutOfMemoryError | IllegalArgumentException e) {
            return null;
        }
        if (decoded != null) mImageBitmaps.put(data.id, new CachedImageBitmap(data, decoded));
        return decoded;
    }

    /**
     * Paint one text run.
     *
     * @param canvas        target canvas
     * @param text          the row text buffer
     * @param palette       the emulator indexed colors
     * @param y             baseline of the row
     * @param startColumn   first grid column of the run
     * @param runWidthColumns run width in grid columns
     * @param startCharIndex first char index of the run
     * @param runWidthChars number of Java chars of the run
     * @param mes           measured width of the run in pixels
     * @param cursor        cursor color, or 0 when the run does not hold the cursor
     * @param cursorStyle   cursor shape
     * @param textStyle     the {@link TextStyle}-encoded style of the run
     * @param reverseVideo  whether the run paints with swapped colors
     * @param forceUnderline whether the run underlines regardless of its style (an OSC 8
     *                       hyperlink or a plain-text URL span, Spec 003 RF-3)
     * @param drawBackground whether the cell background must be painted; false for a run over a
     *                       {@code z<0} image, whose background the image pass already painted
     *                       (RF-19/RF-23)
     */
    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, boolean forceUnderline, boolean drawBackground) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0 || forceUnderline;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (drawBackground && backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background. A cell covered by a below-text placement (RF-23)
            // keeps its glyph but not its background, which the image pass already painted.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
