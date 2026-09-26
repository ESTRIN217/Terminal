package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.util.LruCache;

import com.termux.terminal.KittyPlaceholderDecoder;
import com.termux.terminal.KittyVirtualPlacement;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalImageData;
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

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

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

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunHyperlink = 0;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

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

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                // Break the run when style, cursor, selection or OSC 8 hyperlink changes, when this code point
                // (or the one that started the run) has a width that does not match wcwidth(), or
                // when ligatures are disabled, in which case every code point must be shaped alone
                // so that the font's ligature tables (GSUB liga/clig) can never combine characters.
                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection
                    || hyperlink != lastRunHyperlink || fontWidthMismatch || lastRunFontWidthMismatch || !mEnableLigatures) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
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
                            lastRunHyperlink != 0 && mHyperlinksEnabled);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunHyperlink = hyperlink;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
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

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                lastRunHyperlink != 0 && mHyperlinksEnabled);
            // Inline images paint after text so previews sit above leftover cell glyphs;
            // the cursor was already painted inside the run above and stays on top of
            // the image only when it falls outside the image rect (acceptable v1).
            drawImagesForRow(mEmulator, screen, canvas, row, heightOffset,
                lineObject, palette, reverseVideo, selx1, selx2);
            // Unicode placeholders (kitty U+10EEEE): decode + paint after text so the
            // image covers the placeholder glyph; inheritance spans wrapped lines.
            if (mImagesEnabled) {
                if (!(row > topRow && screen.getLineWrap(row - 1))) mPlaceholderState.reset();
                mPlaceholderCanvas = canvas;
                mPlaceholderEmulator = mEmulator;
                mPlaceholderTop = heightOffset - mFontLineSpacing;
                mPlaceholderBottom = heightOffset;
                mPlaceholderLine = lineObject;
                mPlaceholderPalette = palette;
                mPlaceholderReverseVideo = reverseVideo;
                mPlaceholderSelX1 = selx1;
                mPlaceholderSelX2 = selx2;
                KittyPlaceholderDecoder.collectRow(lineObject, columns, mPlaceholderState, mPlaceholderVisitor);
            }
        }
    }

    /**
     * Paint one unicode-placeholder cell as its grid slice of the virtual placement.
     * Silently skips cells whose id or grid position does not resolve — the plain
     * placeholder glyph drawn by the text run stays visible as fallback.
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
        // Blank the cell with its effective background before painting the slice: the
        // placeholder glyph is drawn by the text run underneath and transparent image
        // pixels must show the cell background (kitty spec), never the glyph.
        final boolean reverseHere = mPlaceholderReverseVideo
            || (column >= mPlaceholderSelX1 && column <= mPlaceholderSelX2);
        mTextPaint.setColor(TextStyle.effectiveBackgroundColor(
            mPlaceholderLine.getStyle(column), mPlaceholderPalette, reverseHere));
        canvas.drawRect(left, top, right, bottom, mTextPaint);
        final android.graphics.Rect src = new android.graphics.Rect(
            Math.min(srcLeft, bmpW - 1), Math.min(srcTop, bmpH - 1),
            Math.max(srcLeft + 1, Math.min(srcRight, bmpW)),
            Math.max(srcTop + 1, Math.min(srcBottom, bmpH)));
        final android.graphics.RectF dst = new android.graphics.RectF(left, top, right, bottom);
        canvas.drawBitmap(bitmap, src, dst, null);
    }

    /**
     * Paint every inline image whose placement intersects {@code externalRow}.
     * Each contiguous run of the same image id on the row draws once, with the
     * source band taken from the matching columns of the bitmap. Cells under the
     * strip are blanked with their effective background first (kitty spec:
     * transparent image regions show the cell background, never the glyph).
     *
     * @param emulator     the emulator (image registry)
     * @param screen       the buffer being drawn
     * @param canvas       target canvas
     * @param externalRow  external (transcript-aware) row
     * @param yBottom      bottom of the row (same baseline convention as text runs)
     * @param lineObject   the row being painted (per-cell styles for blanking)
     * @param palette      the emulator indexed colors
     * @param reverseVideo whether the emulator is in reverse-video mode
     * @param selx1        selection left bound for the row (or -1)
     * @param selx2        selection right bound for the row (or -1)
     */
    private void drawImagesForRow(TerminalEmulator emulator, TerminalBuffer screen, Canvas canvas,
                                  int externalRow, float yBottom, TerminalRow lineObject,
                                  int[] palette, boolean reverseVideo, int selx1, int selx2) {
        if (!mImagesEnabled) return;
        final int columns = emulator.mColumns;
        final float top = yBottom - mFontLineSpacing;
        final float bottom = yBottom;
        int col = 0;
        while (col < columns) {
            final int imageId = screen.getImageAt(externalRow, col);
            if (imageId == 0) {
                col++;
                continue;
            }
            int spanEnd = col + 1;
            while (spanEnd < columns && screen.getImageAt(externalRow, spanEnd) == imageId) spanEnd++;
            final TerminalImageData data = emulator.getImageData(imageId);
            if (data != null && data.intersectsRow(externalRow)) {
                drawImageStrip(canvas, data, externalRow, col, spanEnd - col, top, bottom,
                    lineObject, palette, reverseVideo, selx1, selx2);
            }
            col = spanEnd;
        }
    }

    /**
     * Draw one horizontal strip of an image placement.
     *
     * @param canvas      target
     * @param data        registry entry
     * @param externalRow the row being painted
     * @param startColumn first screen column of the strip
     * @param widthCells  strip width in cells
     * @param top         strip top in canvas Y
     * @param bottom      strip bottom in canvas Y
     * @param lineObject  the row being painted (per-cell styles for blanking)
     * @param palette     the emulator indexed colors
     * @param reverseVideo whether the emulator is in reverse-video mode
     * @param selx1       selection left bound for the row (or -1)
     * @param selx2       selection right bound for the row (or -1)
     */
    private void drawImageStrip(Canvas canvas, TerminalImageData data, int externalRow,
                                int startColumn, int widthCells, float top, float bottom,
                                TerminalRow lineObject, int[] palette, boolean reverseVideo,
                                int selx1, int selx2) {
        final Bitmap bitmap = bitmapFor(data);
        if (bitmap == null || bitmap.isRecycled()) return;
        final int bmpW = bitmap.getWidth();
        final int bmpH = bitmap.getHeight();
        final int localRow = externalRow - data.startRow;
        if (localRow < 0 || localRow >= data.cellsH) return;
        final int srcTop = (int) ((long) localRow * bmpH / data.cellsH);
        final int srcBottom = (int) ((long) (localRow + 1) * bmpH / data.cellsH);
        final int srcH = Math.max(1, srcBottom - srcTop);
        // Horizontal source slice matches which columns of the placement remain.
        final int localCol = Math.max(0, startColumn - data.startCol);
        final int srcLeft = (int) ((long) localCol * bmpW / data.cellsW);
        final int srcRight = (int) ((long) (localCol + widthCells) * bmpW / data.cellsW);
        final float left = startColumn * mFontWidth;
        final float right = left + widthCells * mFontWidth;
        // Blank every covered cell with its own effective background before painting:
        // text runs (filename text etc.) are drawn underneath and transparent image
        // pixels must show the cell background, never the leftover glyph.
        for (int c = startColumn; c < startColumn + widthCells; c++) {
            final boolean reverseHere = reverseVideo || (c >= selx1 && c <= selx2);
            mTextPaint.setColor(TextStyle.effectiveBackgroundColor(
                lineObject.getStyle(c), palette, reverseHere));
            final float cellLeft = c * mFontWidth;
            canvas.drawRect(cellLeft, top, cellLeft + mFontWidth, bottom, mTextPaint);
        }
        final android.graphics.Rect src = new android.graphics.Rect(
            Math.min(srcLeft, bmpW - 1), srcTop,
            Math.max(srcLeft + 1, Math.min(srcRight, bmpW)), Math.min(bmpH, srcTop + srcH));
        final android.graphics.RectF dst = new android.graphics.RectF(left, top, right, bottom);
        canvas.drawBitmap(bitmap, src, dst, null);
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

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, boolean isHyperlink) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0 || isHyperlink;
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

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
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
