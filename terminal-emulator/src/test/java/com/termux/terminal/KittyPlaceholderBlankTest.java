package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Placeholder cells that cannot be resolved must still be reported to the renderers
 * (spec 002, RF-1/RF-4/RF-7): a cell holding {@code U+10EEEE} is never painted as text,
 * it is blanked with the cell background instead.
 */
public class KittyPlaceholderBlankTest extends TerminalTestCase {

	private static final String PNG_1x1_B64 =
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	/** {@code U+10EEEE} as a UTF-16 surrogate pair (Java {@code \\u} escapes are 4 hex digits). */
	private static final String PH = "\uDBFB\uDEEE";

	private static String kg(String control, String data) {
		return "\033_G" + control + ";" + data + "\033\\";
	}

	/** Result of scanning one screen row: resolved targets and unresolved placeholder columns. */
	private static final class Scan {
		final List<Integer> resolvedColumns = new ArrayList<>();
		final List<KittyPlaceholderDecoder.Target> targets = new ArrayList<>();
		final List<Integer> unresolvedColumns = new ArrayList<>();
	}

	private Scan scanRow(int externalRow) {
		final TerminalBuffer screen = mTerminal.getScreen();
		final TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow));
		final KittyPlaceholderDecoder.RowState state = new KittyPlaceholderDecoder.RowState();
		final Scan scan = new Scan();
		KittyPlaceholderDecoder.collectRow(line, mTerminal.mColumns, state, (column, target) -> {
			scan.resolvedColumns.add(column);
			scan.targets.add(target);
		}, column -> scan.unresolvedColumns.add(column));
		return scan;
	}

	/** A placeholder with the default foreground is a cell, but not an image target (RF-4). */
	public void testDefaultForegroundPlaceholderIsReportedUnresolved() {
		withTerminalSized(20, 6);
		enterString(PH + "\u0305\u0305");
		final Scan scan = scanRow(0);
		assertTrue(scan.resolvedColumns.isEmpty());
		assertEquals(Collections.singletonList(0), scan.unresolvedColumns);
	}

	/** A resolvable placeholder is a target only: the renderers paint it, never blank it as unknown (RF-2). */
	public void testResolvedPlaceholderIsNotReportedUnresolved() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=42,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=42,c=4,r=4", ""));
		mOutput.getOutputAndClear();
		enterString("\033[38;5;42m" + PH + "\u0305\u0305");
		final Scan scan = scanRow(0);
		assertEquals(Collections.singletonList(0), scan.resolvedColumns);
		assertTrue(scan.unresolvedColumns.isEmpty());
		assertEquals(42, scan.targets.get(0).imageId);
	}

	/**
	 * An unresolved cell is reported and leaves the inheritance state untouched, so the next
	 * resolvable cell on the same line still decodes as if it followed the last valid one
	 * (RF-7).
	 */
	public void testUnresolvedCellDoesNotAdvanceInheritance() {
		withTerminalSized(20, 6);
		enterString(kg("a=t,i=42,f=100,s=1,v=1", PNG_1x1_B64));
		mOutput.getOutputAndClear();
		enterString(kg("a=p,U=1,i=42,c=4,r=4", ""));
		mOutput.getOutputAndClear();
		// Column 0: resolvable, grid (0,0). Column 1: default fg placeholders (no target).
		enterString("\033[38;5;42m" + PH + "\u0305\u0305");
		enterString("\033[0m" + PH + "\u0305");
		// Column 2: resolvable again, row 0 only → inherits column 1 from column 0.
		enterString("\033[38;5;42m" + PH + "\u0305");
		final Scan scan = scanRow(0);
		assertEquals(java.util.Arrays.asList(0, 2), scan.resolvedColumns);
		assertEquals(Collections.singletonList(1), scan.unresolvedColumns);
		assertEquals(42, scan.targets.get(1).imageId);
		assertEquals(0, scan.targets.get(1).gridRow);
		assertEquals(1, scan.targets.get(1).gridCol);
	}

	/** Ordinary text never reaches the placeholder visitors. */
	public void testPlainTextIsNotReported() {
		withTerminalSized(20, 6);
		enterString("hello");
		final Scan scan = scanRow(0);
		assertTrue(scan.resolvedColumns.isEmpty());
		assertTrue(scan.unresolvedColumns.isEmpty());
	}
}
