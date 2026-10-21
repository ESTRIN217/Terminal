package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects plain-text URLs printed in the terminal and maps them to tappable/underlineable
 * cells, reusing the same recognizer as the extract-URLs dialog ({@code TermuxUrlUtils}, which
 * now delegates to {@link #getUrlPattern()}).
 *
 * <p>Detection runs over <em>logical</em> lines: a line the app printed that was wrapped by
 * the soft-wrap continues on the next screen row through the buffer's line-wrap flag, so a URL
 * split across rows is one span and a tap on any of its cells opens the full URL. Trailing
 * spaces are kept when joining rows because the character offsets of every cell (via
 * {@link TerminalRow#findStartOfColumn}) must line up with the joined text the recognizer sees;
 * the recognizer itself cannot match past a space, so padding never inflates a URL.</p>
 *
 * <p>The recognizer is only re-run (RNF-1) when the content fingerprint of the logical line —
 * the per-row text hash plus the wrap flag and width — changes; scroll shifts do not
 * invalidate anything because the key is content, not row positions. Entries only carry the
 * spans; cell lookup always re-reads the live buffer, so an overwritten cell stops opening
 * its underline as soon as its row's fingerprint changes.</p>
 *
 * <p>This is UI-thread-only state (drawing and taps); there is no locking by design. The
 * class is stateless about the terminal: it reads the buffer through its public API and adds
 * no URL knowledge to {@link TerminalEmulator} or {@link TerminalBuffer}.</p>
 */
public final class TerminalUrlScanner {

    /**
     * Cache size per terminal buffer: a handful of screens' worth of logical lines.
     * Eviction only costs a rescan of one line's cost the next time it is looked up.
     */
    private static final int CACHE_SIZE = 64;

    /**
     * Safety limit for backwards/forwards walking of the soft-wrap chain, honoured even if a
     * broken writer left wrap flags set around the whole transcript ring.
     */
    private static final int MAX_LINE_ROWS = 100;

    /** Per-buffer cache of content-fingerprint to the spans of one logical line. */
    private final Map<TerminalBuffer, LinkedHashMap<Fingerprint, LogicalLineEntry>> mCaches =
        new IdentityHashMap<>();

    /** Number of times the recognizer was actually run (test hook for the cache). */
    private int mRescanCount;

    /** The recognizer pattern, compiled once (both delegation and matching must share it). */
    private static Pattern sUrlPattern;

    /**
     * The URL recognizer shared with {@code TermuxUrlUtils} (moved here verbatim so the
     * dialog, the tap path and the underline path can never diverge).
     *
     * @return the transcript recognizer's pattern
     */
    @NonNull
    public static Pattern getUrlPattern() {
        if (sUrlPattern == null) {
            sUrlPattern = compileUrlPattern();
        }
        return sUrlPattern;
    }

    @NonNull
    private static Pattern compileUrlPattern() {
        StringBuilder regexSb = new StringBuilder();

        regexSb.append("(");                       // Begin first matching group.
        regexSb.append("(?:");                     // Begin scheme group.
        regexSb.append("dav|");                    // The DAV proto.
        regexSb.append("dict|");                   // The DICT proto.
        regexSb.append("dns|");                    // The DNS proto.
        regexSb.append("file|");                    // File path.
        regexSb.append("finger|");                 // The Finger proto.
        regexSb.append("ftp(?:s?)|");              // The FTP proto.
        regexSb.append("git|");                    // The Git proto.
        regexSb.append("gemini|");                 // The Gemini proto.
        regexSb.append("gopher|");                 // The Gopher proto.
        regexSb.append("http(?:s?)|");             // The HTTP proto.
        regexSb.append("imap(?:s?)|");             // The IMAP proto.
        regexSb.append("irc(?:[6s]?)|");           // The IRC proto.
        regexSb.append("ip[fn]s|");                // The IPFS proto.
        regexSb.append("ldap(?:s?)|");             // The LDAP proto.
        regexSb.append("pop3(?:s?)|");             // The POP3 proto.
        regexSb.append("redis(?:s?)|");            // The Redis proto.
        regexSb.append("rsync|");                  // The Rsync proto.
        regexSb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regexSb.append("sftp|");                   // The SFTP proto.
        regexSb.append("smb(?:s?)|");              // The SAMBA proto.
        regexSb.append("smtp(?:s?)|");             // The SMTP proto.
        regexSb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regexSb.append("tcp|");                    // The TCP proto.
        regexSb.append("telnet|");                 // The Telnet proto.
        regexSb.append("tftp|");                   // The TFTP proto.
        regexSb.append("udp|");                    // The UDP proto.
        regexSb.append("vnc|");                    // The VNC proto.
        regexSb.append("ws(?:s?)");                // The Websocket proto.
        regexSb.append(")://");                    // End scheme group.
        regexSb.append(")");                       // End first matching group.

        // Begin second matching group.
        regexSb.append("(");

        // User name and/or password in format 'user:pass@'.
        regexSb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regexSb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regexSb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regexSb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*){1,}[a-z\\u00a1-\\uffff0-9]{1,}))?|");

        // Just path. Used in case of 'file://' scheme.
        regexSb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");

        // End host group.
        regexSb.append(")");

        // Port number.
        regexSb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regexSb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // Fragment.
        regexSb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // End second matching group.
        regexSb.append(")");

        return Pattern.compile(
            regexSb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
    }

    /**
     * A soft-wrapped logical line: the joined row texts plus the character offset at which
     * every participating row starts in {@link #mText}, so character spans can be mapped back
     * to specific rows and, with {@link TerminalRow#findStartOfColumn}, to cells.
     */
    public static final class LogicalLine {
        /** Joined row contents (each row padded to the terminal width), never {@code null}. */
        public final String mText;
        /** External row where the line starts (negative for transcript rows). */
        public final int mFirstRow;
        /** Number of screen rows the line spans, at least 1. */
        public final int mRowCount;
        /** Character index in {@link #mText} where each participating row starts (length {@link #mRowCount}). */
        final int[] mRowStartOffsets;

        LogicalLine(String text, int firstRow, int[] rowStartOffsets) {
            mText = text;
            mFirstRow = firstRow;
            mRowCount = rowStartOffsets.length;
            mRowStartOffsets = rowStartOffsets;
        }
    }

    /** One URL found in a logical line, expressed as a character range of the joined text. */
    public static final class LinkSpan {
        /** The URI the recognizer matched, exactly what the dialog would copy then. */
        public final String mUri;
        /** First character index of the span in the joined line text. */
        final int mStartChar;
        /** Character index just past the span in the joined line text. */
        final int mEndChar;

        LinkSpan(String uri, int startChar, int endChar) {
            mUri = uri;
            mStartChar = startChar;
            mEndChar = endChar;
        }
    }

    /** Cached artifact for one logical line: the line plus its URL spans. */
    public static final class LogicalLineEntry {
        /** The logical line the spans belong to, never {@code null}. */
        public final LogicalLine mLine;
        /** All URL spans found in the line; length 0 is normal (most lines have no URL). */
        public final LinkSpan[] mSpans;

        LogicalLineEntry(LogicalLine line, LinkSpan[] spans) {
            mLine = line;
            mSpans = spans;
        }

        /**
         * The column range ({@code [start, end)} columns of the row) that a span covers on
         * one row, for underlining: a cell counts as covered when its glyph starts inside the
         * span, which makes both halves of a wide glyph underline (and tap) together.
         *
         * @param buffer      the terminal buffer the line belongs to
         * @param externalRow the row to map to
         * @param span        the span to map
         * @return {@code {firstColumn, endColumnExclusive}} or {@code null} when the span
         * does not touch the row
         */
        @Nullable
        public int[] columnRangeForRow(@NonNull TerminalBuffer buffer, int externalRow,
                                       @NonNull LinkSpan span) {
            int rowOffset = externalRow - mLine.mFirstRow;
            if (rowOffset < 0 || rowOffset >= mLine.mRowCount) return null;
            int localStart = mLine.mRowStartOffsets[rowOffset];
            int localEnd = rowOffset + 1 < mLine.mRowCount
                ? mLine.mRowStartOffsets[rowOffset + 1] : mLine.mText.length();
            int spanStart = Math.max(span.mStartChar, localStart) - localStart;
            int spanEnd = Math.min(span.mEndChar, localEnd) - localStart;
            if (spanStart >= spanEnd) return null;
            TerminalRow row = buffer.mLines[buffer.externalToInternalRow(externalRow)];
            int firstColumn = -1;
            int lastColumn = -2;
            for (int column = 0; column < buffer.mColumns; column++) {
                int glyphIndex = row.findStartOfColumn(column);
                if (glyphIndex >= spanStart && glyphIndex < spanEnd) {
                    if (firstColumn < 0) firstColumn = column;
                    lastColumn = column;
                }
            }
            if (firstColumn < 0) return null;
            return new int[] {firstColumn, lastColumn + 1};
        }
    }

    /**
     * Collect the logical (soft-wrap joined) line that an external row participates in.
     *
     * @param buffer      the terminal buffer
     * @param externalRow the row in the external coordinate system
     * @return the line, or {@code null} when the row is outside the live grid
     */
    @Nullable
    public LogicalLine collectLogicalLine(@NonNull TerminalBuffer buffer, int externalRow) {
        if (externalRow < -buffer.getActiveTranscriptRows() || externalRow >= buffer.mScreenRows) {
            return null;
        }
        int first = externalRow;
        int last = externalRow;
        int backLimit = MAX_LINE_ROWS;
        while (first > -buffer.getActiveTranscriptRows() && backLimit-- > 0
            && buffer.getLineWrap(first - 1)) {
            first--;
        }
        int forwardLimit = MAX_LINE_ROWS;
        while (last < buffer.mScreenRows - 1 && forwardLimit-- > 0 && buffer.getLineWrap(last)) {
            last++;
        }
        int rowCount = last - first + 1;
        int[] rowStartOffsets = new int[rowCount];
        StringBuilder text = new StringBuilder();
        for (int row = first, offset = 0; row <= last; row++) {
            TerminalRow line = buffer.mLines[buffer.externalToInternalRow(row)];
            text.append(line.mText, 0, line.getSpaceUsed());
            rowStartOffsets[row - first] = offset;
            offset += line.getSpaceUsed();
        }
        return new LogicalLine(text.toString(), first, rowStartOffsets);
    }

    /**
     * Run the recognizer over a logical line once (callers go through {@link #entryFor} so
     * the cache decides when this happens, see {@link #getRescanCount()}).
     *
     * @param line the joined logical line
     * @return the URL spans of the line, possibly empty
     */
    @NonNull
    public List<LinkSpan> findUrlSpans(@NonNull LogicalLine line) {
        List<LinkSpan> spans = new ArrayList<>();
        Matcher matcher = getUrlPattern().matcher(line.mText);
        while (matcher.find()) {
            spans.add(new LinkSpan(line.mText.substring(matcher.start(), matcher.end()),
                matcher.start(), matcher.end()));
        }
        return spans;
    }

    /**
     * The cached spans and line of a row's logical line, rescanning only when the content
     * fingerprint of the line changed (RNF-1). The fingerprint is the per-row text hash plus
     * its wrap flag and the width: scroll reorders rows but fingerprints of whole lines stay
     * put because every participating row's content hash is the same.
     *
     * @param buffer      the terminal buffer
     * @param externalRow the target row, used both to locate the line and as its cache key
     * @return the cached entry, or {@code null} when the row is outside the live grid
     */
    @Nullable
    public LogicalLineEntry entryFor(@NonNull TerminalBuffer buffer, int externalRow) {
        if (externalRow < -buffer.getActiveTranscriptRows() || externalRow >= buffer.mScreenRows) {
            return null;
        }
        // Rebuild the logical-line row range (cheap flag reads) to fingerprint it.
        int first = externalRow;
        int last = externalRow;
        int backLimit = MAX_LINE_ROWS;
        while (first > -buffer.getActiveTranscriptRows() && backLimit-- > 0
            && buffer.getLineWrap(first - 1)) {
            first--;
        }
        int forwardLimit = MAX_LINE_ROWS;
        while (last < buffer.mScreenRows - 1 && forwardLimit-- > 0 && buffer.getLineWrap(last)) {
            last++;
        }
        int rowCount = last - first + 1;
        Fingerprint fingerprint = new Fingerprint(fingerprintRows(buffer, first, rowCount));

        LinkedHashMap<Fingerprint, LogicalLineEntry> cache =
            mCaches.computeIfAbsent(buffer, b -> newLruCache());
        LogicalLineEntry cached = cache.get(fingerprint);
        if (cached != null) return cached;

        LogicalLine line = collectRange(buffer, first, rowCount);
        List<LinkSpan> spans = findUrlSpans(line);
        mRescanCount++;
        LogicalLineEntry entry =
            new LogicalLineEntry(line, spans.toArray(new LinkSpan[0]));
        cache.put(fingerprint, entry);
        return entry;
    }

    /**
     * The URI at a tapped cell, considering the whole logical line the cell belongs to: a tap
     * on any row of a soft-wrapped URL opens the complete URL.
     *
     * @param buffer      the terminal buffer
     * @param externalRow the tapped row in the external coordinate system
     * @param column      the tapped column (0-based)
     * @return the full URI of the span covering the cell, or {@code null} when no span does
     * or when the row/column is outside the grid
     */
    @Nullable
    public String getUriAt(@NonNull TerminalBuffer buffer, int externalRow, int column) {
        if (column < 0 || column >= buffer.mColumns) return null;
        LogicalLineEntry entry = entryFor(buffer, externalRow);
        if (entry == null) return null;
        int rowOffset = externalRow - entry.mLine.mFirstRow;
        if (rowOffset < 0 || rowOffset >= entry.mLine.mRowCount) return null;
        TerminalRow row = buffer.mLines[buffer.externalToInternalRow(externalRow)];
        int joinedIndex = row.findStartOfColumn(column) + entry.mLine.mRowStartOffsets[rowOffset];
        for (LinkSpan span : entry.mSpans) {
            if (joinedIndex >= span.mStartChar && joinedIndex < span.mEndChar) return span.mUri;
        }
        return null;
    }

    /**
     * How many times the recognizer was actually run since this scanner was created. Test
     * hook for the cache: unchanged content must not rescan.
     *
     * @return the total rescan count
     */
    public int getRescanCount() {
        return mRescanCount;
    }

    /** Per-row fingerprints for a logical line starting at {@code first} with {@code rowCount} rows. */
    private int[] fingerprintRows(@NonNull TerminalBuffer buffer, int first, int rowCount) {
        int[] rowFingerprints = new int[rowCount];
        for (int row = first, i = 0; row < first + rowCount; row++, i++) {
            TerminalRow line = buffer.mLines[buffer.externalToInternalRow(row)];
            // Hash the padding too: a cell that turns into a space changes the line.
            int rowHash = fnv1a(line.mText, line.getSpaceUsed());
            rowFingerprints[i] = rowHash * 2 + (line.mLineWrap ? 1 : 0);
        }
        return rowFingerprints;
    }

    /**
     * FNV-1a over the first {@code limit} chars — the terminal has no
     * {@code Arrays.hashCode(char[], from, to)} and a per-frame fingerprint must not copy the
     * row just to hash it. A collision would only freeze stale spans until the line changes,
     * and is astronomically unlikely for terminal row content.
     */
    private static int fnv1a(char[] text, int limit) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < limit; i++) {
            hash = (hash ^ text[i]) * 0x01000193;
        }
        return hash;
    }

    /** Build the logical line for an already-computed row range (no bounds re-checks). */
    @NonNull
    private LogicalLine collectRange(@NonNull TerminalBuffer buffer, int first, int rowCount) {
        int[] rowStartOffsets = new int[rowCount];
        StringBuilder text = new StringBuilder();
        for (int row = first, offset = 0; row < first + rowCount; row++) {
            TerminalRow line = buffer.mLines[buffer.externalToInternalRow(row)];
            text.append(line.mText, 0, line.getSpaceUsed());
            rowStartOffsets[row - first] = offset;
            offset += line.getSpaceUsed();
        }
        return new LogicalLine(text.toString(), first, rowStartOffsets);
    }

    @NonNull
    private static LinkedHashMap<Fingerprint, LogicalLineEntry> newLruCache() {
        return new LinkedHashMap<Fingerprint, LogicalLineEntry>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Fingerprint, LogicalLineEntry> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    /** Cache key: the whole logical line as row fingerprints (content hash + wrap flag). */
    private static final class Fingerprint {
        private final int[] mRowFingerprints;

        Fingerprint(int[] rowFingerprints) {
            mRowFingerprints = rowFingerprints;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Fingerprint
                && Arrays.equals(mRowFingerprints, ((Fingerprint) other).mRowFingerprints);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mRowFingerprints);
        }
    }
}
