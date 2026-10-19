package com.termux.terminal;

import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

/**
 * Pure geometry of kitty image placements: source crop resolution, destination cell
 * counts, clipping to the visible area, per-cell source pixel bands, stacking order and
 * cell coverage (plan §7.1, RNF-2). No emulator, no Android, no state: every input is a
 * parameter, including the creation sequence used by RF-20.
 */
public class KittyPlacementGeometryTest extends TestCase {

	/** Asserts a {@code {…}} result against the literal {@code {…}} array. */
	private static void assertRect(String message, int[] expected, int[] actual) {
		assertNotNull(message + " — expected " + Arrays.toString(expected) + " but was null", actual);
		assertEquals(message, Arrays.toString(expected), Arrays.toString(actual));
	}

	// ---------------------------------------------------------------- RF-16, RF-12..15

	public void testResolveSourceCropAbsentCropIsTheWholeImage() {
		// RF-16: no crop means the full image, which is what yazi relies on.
		assertRect("no crop = full image", new int[]{0, 0, 100, 50},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 0, 0, 0, 0));
	}

	public void testResolveSourceCropOmittedWidthAndHeightUseRestOfImage() {
		// RF-12: w/h omitted (or non-positive) extend to the image border from x/y.
		assertRect("x/y only", new int[]{20, 10, 80, 40},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 20, 10, 0, 0));
		assertRect("w given, h omitted", new int[]{20, 10, 30, 40},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 20, 10, 30, 0));
		assertRect("h given, w omitted", new int[]{20, 10, 80, 5},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 20, 10, 0, 5));
	}

	public void testResolveSourceCropNegativeOriginClampsToZero() {
		// RF-13: x/y below zero are treated as 0.
		assertRect("negative x/y are 0", new int[]{0, 0, 30, 20},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, -20, -10, 30, 20));
		assertRect("negative y only", new int[]{5, 0, 10, 8},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 5, -7, 10, 8));
	}

	public void testResolveSourceCropIsIntersectedWithTheImage() {
		// RF-14: a crop bigger than the image is clipped, never enlarged.
		assertRect("oversized crop", new int[]{80, 40, 20, 10},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 80, 40, 50, 50));
		assertRect("oversized on both axes", new int[]{90, 45, 10, 5},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 90, 45, 100, 100));
	}

	public void testResolveSourceCropEmptyIntersectionHasZeroSize() {
		// RF-15: empty intersection keeps the requested origin but reports no pixels.
		assertRect("origin past the right edge", new int[]{120, 60, 0, 0},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 120, 60, 10, 10));
		assertRect("origin at the right edge", new int[]{100, 0, 0, 10},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 100, 0, 10, 10));
		assertRect("origin at the bottom edge", new int[]{0, 50, 10, 0},
			KittyPlacementGeometry.resolveSourceCrop(100, 50, 0, 50, 10, 10));
	}

	public void testResolveSourceCropOnePixelImage() {
		assertRect("whole 1x1", new int[]{0, 0, 1, 1},
			KittyPlacementGeometry.resolveSourceCrop(1, 1, 0, 0, 0, 0));
		assertRect("explicit 1x1", new int[]{0, 0, 1, 1},
			KittyPlacementGeometry.resolveSourceCrop(1, 1, 0, 0, 1, 1));
		assertRect("1x1 origin outside", new int[]{1, 1, 0, 0},
			KittyPlacementGeometry.resolveSourceCrop(1, 1, 1, 1, 0, 0));
	}

	public void testResolveSourceCropUnknownImageSizeHasNoPixels() {
		// Unknown pixel size (0) cannot host any crop: zero-sized result, never a crash.
		assertRect("unknown size, no crop", new int[]{0, 0, 0, 0},
			KittyPlacementGeometry.resolveSourceCrop(0, 0, 0, 0, 0, 0));
		assertRect("unknown size, explicit crop", new int[]{3, 4, 0, 0},
			KittyPlacementGeometry.resolveSourceCrop(0, 0, 3, 4, 8, 8));
	}

	// -------------------------------------------------------------------- RF-7, 9, 10

	public void testResolveCellsUsesTheRequestedCountsExactly() {
		// RF-7: c= and r= are used verbatim, even when the aspect does not match.
		assertRect("explicit c/r ignores aspect", new int[]{6, 3},
			KittyPlacementGeometry.resolveCells(6, 3, 1000, 10, 10, 20));
		assertRect("explicit c/r without pixel metrics", new int[]{4, 2},
			KittyPlacementGeometry.resolveCells(4, 2, 0, 0, 0, 0));
	}

	public void testResolveCellsDerivesTheMissingDimensionFromAspect() {
		// RF-9: the missing side is derived from the origin aspect ratio, ceiling.
		assertRect("r from c", new int[]{6, 3},
			KittyPlacementGeometry.resolveCells(6, 0, 8, 4, 10, 10));
		assertRect("c from r", new int[]{6, 3},
			KittyPlacementGeometry.resolveCells(0, 3, 8, 4, 10, 10));
		assertRect("both derived from pixels", new int[]{3, 1},
			KittyPlacementGeometry.resolveCells(0, 0, 25, 10, 10, 10));
		assertRect("both derived, tall image", new int[]{1, 10},
			KittyPlacementGeometry.resolveCells(0, 0, 5, 100, 10, 10));
	}

	public void testResolveCellsRoundsUpAndNeverGoesBelowOneCell() {
		// RF-9: ceil and a minimum of 1 cell on the derived side.
		assertRect("ceil of 2.6 cells", new int[]{3, 1},
			KittyPlacementGeometry.resolveCells(0, 0, 21, 10, 10, 10));
		assertRect("derived side clamped to 1 cell", new int[]{6, 1},
			KittyPlacementGeometry.resolveCells(6, 0, 8, 4, 10, 40));
		assertRect("derived side from 1 px", new int[]{1, 1},
			KittyPlacementGeometry.resolveCells(0, 0, 1, 1, 100, 100));
	}

	public void testResolveCellsWithoutCountsAndWithoutPixelsIsInvalid() {
		// RF-10: neither c=/r= nor known pixels => the placement cannot be resolved.
		assertNull("0/0 pixels", KittyPlacementGeometry.resolveCells(0, 0, 0, 0, 10, 10));
		assertNull("pixel width only", KittyPlacementGeometry.resolveCells(0, 0, 100, 0, 10, 10));
		assertNull("pixel height only", KittyPlacementGeometry.resolveCells(0, 0, 0, 100, 10, 10));
		assertNull("missing metrics too", KittyPlacementGeometry.resolveCells(0, 0, 0, 0, 0, 0));
	}

	public void testResolveCellsWithUnknownCellMetricsUsesOnePixelPerCell() {
		// Spec clarification 2: cellPxW/cellPxH = 0 means 1 pixel = 1 cell, no division by zero.
		assertRect("c from r with unknown metrics", new int[]{6, 3},
			KittyPlacementGeometry.resolveCells(0, 3, 8, 4, 0, 0));
		assertRect("both derived with unknown metrics", new int[]{25, 10},
			KittyPlacementGeometry.resolveCells(0, 0, 25, 10, 0, 0));
		assertRect("r from c with unknown metrics", new int[]{6, 3},
			KittyPlacementGeometry.resolveCells(6, 0, 8, 4, 0, 0));
		assertRect("one missing, no pixel size: known side is reused", new int[]{3, 3},
			KittyPlacementGeometry.resolveCells(0, 3, 0, 0, 10, 10));
	}

	// ----------------------------------------------------------------------- RF-8, 31

	public void testClipInteriorRectangleKeepsItsCells() {
		assertRect("fully visible", new int[]{2, 7, 3, 7},
			KittyPlacementGeometry.clipToVisibleArea(2, 3, 4, 5, 0, 10, 80));
	}

	public void testClipCrossesRightEdge() {
		assertRect("right edge", new int[]{2, 7, 70, 80},
			KittyPlacementGeometry.clipToVisibleArea(2, 70, 20, 5, 0, 10, 80));
	}

	public void testClipCrossesBottomEdge() {
		assertRect("bottom edge", new int[]{8, 10, 3, 7},
			KittyPlacementGeometry.clipToVisibleArea(8, 3, 4, 5, 0, 10, 80));
	}

	public void testClipCrossesBothEdges() {
		assertRect("both edges", new int[]{8, 10, 70, 80},
			KittyPlacementGeometry.clipToVisibleArea(8, 70, 20, 5, 0, 10, 80));
	}

	public void testClipOriginAtLastColumnAndRowStillShowsOneCell() {
		assertRect("last cell of the grid", new int[]{9, 10, 79, 80},
			KittyPlacementGeometry.clipToVisibleArea(9, 79, 3, 3, 0, 10, 80));
	}

	public void testClipRectangleOutsideTheVisibleAreaIsNull() {
		assertNull("below the last visible row", KittyPlacementGeometry.clipToVisibleArea(10, 3, 4, 5, 0, 10, 80));
		assertNull("scrolled out above", KittyPlacementGeometry.clipToVisibleArea(-4, 3, 4, 3, 0, 10, 80));
		assertNull("past the last column", KittyPlacementGeometry.clipToVisibleArea(2, 80, 4, 5, 0, 10, 80));
		assertNull("far past the last column", KittyPlacementGeometry.clipToVisibleArea(2, 900, 4, 5, 0, 10, 80));
	}

	public void testClipHugeRectangleIsClampedWithoutOverflow() {
		assertRect("c/r larger than the screen", new int[]{5, 10, 2, 80},
			KittyPlacementGeometry.clipToVisibleArea(5, 2, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 10, 80));
	}

	public void testClipTranscriptRowIsVisibleWhileScrolled() {
		assertRect("transcript row inside the viewport", new int[]{-2, 1, 1, 6},
			KittyPlacementGeometry.clipToVisibleArea(-2, 1, 5, 3, -3, 6, 80));
		assertRect("partly above the viewport", new int[]{-3, -2, 1, 6},
			KittyPlacementGeometry.clipToVisibleArea(-5, 1, 5, 3, -3, 6, 80));
	}

	public void testClipDegenerateRectangleIsNull() {
		assertNull("zero width", KittyPlacementGeometry.clipToVisibleArea(2, 3, 0, 5, 0, 10, 80));
		assertNull("zero height", KittyPlacementGeometry.clipToVisibleArea(2, 3, 4, 0, 0, 10, 80));
		assertNull("negative width", KittyPlacementGeometry.clipToVisibleArea(2, 3, -4, 5, 0, 10, 80));
		assertNull("empty viewport rows", KittyPlacementGeometry.clipToVisibleArea(2, 3, 4, 5, 0, 0, 80));
		assertNull("empty viewport columns", KittyPlacementGeometry.clipToVisibleArea(2, 3, 4, 5, 0, 10, 0));
	}

	// ------------------------------------------------------------- RF-11, 14, 16, 17

	public void testBandWithoutCropMatchesFullImageMapping() {
		// RF-16: no crop => the band is exactly what the pre-placements strip painter
		// computed for the whole bitmap (this is what keeps yazi unchanged, RF-35).
		assertRect("first row", new int[]{0, 0, 100, 10},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 100, 50, 10, 5, 0, 0, 10));
		assertRect("middle row", new int[]{0, 10, 100, 20},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 100, 50, 10, 5, 1, 0, 10));
		assertRect("last row reaches the border", new int[]{0, 40, 100, 50},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 100, 50, 10, 5, 4, 0, 10));
	}

	public void testBandWithoutCropMapsPartialStrip() {
		assertRect("columns 2..4 of row 1", new int[]{20, 10, 50, 20},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 100, 50, 10, 5, 1, 2, 3));
	}

	public void testBandWithCropIsOffsetIntoTheImage() {
		// RF-11/RF-14: only the source crop is scaled, independently per axis.
		assertRect("crop row 0", new int[]{20, 10, 50, 20},
			KittyPlacementGeometry.sourcePixelBand(20, 10, 30, 20, 3, 2, 0, 0, 3));
		assertRect("crop row 1", new int[]{20, 20, 50, 30},
			KittyPlacementGeometry.sourcePixelBand(20, 10, 30, 20, 3, 2, 1, 0, 3));
		assertRect("crop middle column", new int[]{30, 10, 40, 20},
			KittyPlacementGeometry.sourcePixelBand(20, 10, 30, 20, 3, 2, 0, 1, 1));
	}

	public void testBandRowsCoverTheCropWithoutGapsOrOverlap() {
		// RF-17: whole-cell mapping; consecutive rows tile the crop and the last reaches it.
		final int cropY = 0, cropH = 10, cellsH = 3;
		int previousBottom = -1;
		for (int row = 0; row < cellsH; row++) {
			final int[] band = KittyPlacementGeometry.sourcePixelBand(0, cropY, 10, cropH, 1, cellsH, row, 0, 1);
			assertNotNull("row " + row, band);
			assertTrue("row " + row + " keeps at least 1 px", band[3] > band[1]);
			assertEquals("row " + row + " top", row == 0 ? cropY : previousBottom, band[1]);
			if (row == cellsH - 1) assertEquals("last row reaches the crop border", cropY + cropH, band[3]);
			previousBottom = band[3];
		}
	}

	public void testBandColumnsCoverTheCropWithoutGapsOrOverlap() {
		final int cropX = 0, cropW = 10, cellsW = 3;
		int previousRight = -1;
		for (int col = 0; col < cellsW; col++) {
			final int[] band = KittyPlacementGeometry.sourcePixelBand(cropX, 0, cropW, 10, cellsW, 1, 0, col, 1);
			assertNotNull("col " + col, band);
			assertTrue("col " + col + " keeps at least 1 px", band[2] > band[0]);
			assertEquals("col " + col + " left", col == 0 ? cropX : previousRight, band[0]);
			if (col == cellsW - 1) assertEquals("last col reaches the crop border", cropX + cropW, band[2]);
			previousRight = band[2];
		}
	}

	public void testBandKeepsAtLeastOnePixelPerAxis() {
		// More cells than pixels: the band is never degenerate (1 px minimum).
		assertRect("first row of a 2 px crop in 5 cells", new int[]{0, 0, 2, 1},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 2, 2, 5, 5, 0, 0, 5));
		assertRect("last cell of a 2 px crop in 5 cells", new int[]{1, 1, 2, 2},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 2, 2, 5, 5, 4, 4, 1));
	}

	public void testBandClampsCellsOutsideTheRectangle() {
		assertRect("row/column/span out of range are clamped", new int[]{5, 5, 10, 10},
			KittyPlacementGeometry.sourcePixelBand(0, 0, 10, 10, 2, 2, 9, 9, 9));
	}

	public void testBandWithEmptyCropOrCellsIsNull() {
		assertNull("empty crop width", KittyPlacementGeometry.sourcePixelBand(0, 0, 0, 10, 3, 3, 0, 0, 3));
		assertNull("empty crop height", KittyPlacementGeometry.sourcePixelBand(0, 0, 10, 0, 3, 3, 0, 0, 3));
		assertNull("empty cells width", KittyPlacementGeometry.sourcePixelBand(0, 0, 10, 10, 0, 3, 0, 0, 3));
		assertNull("empty cells height", KittyPlacementGeometry.sourcePixelBand(0, 0, 10, 10, 3, 0, 0, 0, 3));
	}

	// -------------------------------------------------------------------------- RF-18

	public void testIsBelowTextOnlyForNegativeZ() {
		assertTrue("z=-1 is below the glyphs", KittyPlacementGeometry.isBelowText(-1));
		assertTrue("z=Integer.MIN_VALUE is below the glyphs", KittyPlacementGeometry.isBelowText(Integer.MIN_VALUE));
		assertFalse("z absent means 0 and paints above", KittyPlacementGeometry.isBelowText(0));
		assertFalse("z=1 paints above", KittyPlacementGeometry.isBelowText(1));
		assertFalse("z=Integer.MAX_VALUE paints above", KittyPlacementGeometry.isBelowText(Integer.MAX_VALUE));
	}

	// -------------------------------------------------------------------- RF-20, RF-37

	public void testCompareOrdersByZAscending() {
		assertTrue("z=0 before z=1", KittyPlacementGeometry.comparePlacementOrder(0, 1, 1, 1) < 0);
		assertTrue("z=1 after z=0", KittyPlacementGeometry.comparePlacementOrder(1, 1, 0, 1) > 0);
		assertTrue("z=-5 before z=-1", KittyPlacementGeometry.comparePlacementOrder(-5, 100, -1, 1) < 0);
		assertTrue("very negative z paints first", KittyPlacementGeometry.comparePlacementOrder(
			Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0) < 0);
	}

	public void testCompareBreaksZTieByLaterCreation() {
		// RF-20: same z => the later creation wins (is painted on top).
		assertTrue("later creation wins", KittyPlacementGeometry.comparePlacementOrder(0, 7, 0, 3) > 0);
		assertTrue("earlier creation loses", KittyPlacementGeometry.comparePlacementOrder(0, 3, 0, 7) < 0);
		assertEquals("same z and same creation is equal", 0,
			KittyPlacementGeometry.comparePlacementOrder(0, 5, 0, 5));
	}

	public void testCompareDoesNotOverflowWithLargeSequences() {
		// RF-20/RF-37: 257 placements and huge sequences must not overflow the comparison.
		assertTrue("later of 257 placements wins", KittyPlacementGeometry.comparePlacementOrder(0, 257L, 0, 256L) > 0);
		assertTrue("Long.MIN_VALUE loses", KittyPlacementGeometry.comparePlacementOrder(
			0, Long.MIN_VALUE + 1, 0, Long.MAX_VALUE) < 0);
		assertTrue("Long.MAX_VALUE wins", KittyPlacementGeometry.comparePlacementOrder(
			0, Long.MAX_VALUE, 0, Long.MIN_VALUE) > 0);
		assertTrue("z still dominates the sequence", KittyPlacementGeometry.comparePlacementOrder(
			1, 257L, 0, Long.MAX_VALUE) > 0);
	}

	// -------------------------------------------------------------------- RF-21, RF-27

	public void testCoversCellIncludesOriginAndExcludesEnd() {
		assertTrue("top-left cell", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 2, 3));
		assertTrue("bottom-right cell", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 6, 6));
		assertFalse("first column past the right edge", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 2, 7));
		assertFalse("first row past the bottom edge", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 7, 3));
		assertFalse("one row above the origin", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 1, 3));
		assertFalse("one column left of the origin", KittyPlacementGeometry.coversCell(2, 3, 4, 5, 6, 2));
	}

	public void testCoversCellEmptyRectangleCoversNothing() {
		// RF-15: a placement with no pixels reserves no cells.
		assertFalse("zero width", KittyPlacementGeometry.coversCell(2, 3, 0, 5, 2, 3));
		assertFalse("zero height", KittyPlacementGeometry.coversCell(2, 3, 4, 0, 2, 3));
	}

	public void testCoversCellOverlapIsCoveredByBothPlacements() {
		// RF-21/RF-27: overlapping rectangles both cover the shared cell; RF-20 decides
		// which one is the topmost (later creation here).
		final int[] first = {0, 0, 4, 2};
		final int[] second = {1, 2, 4, 2};
		assertTrue("first covers the shared cell", KittyPlacementGeometry.coversCell(
			first[0], first[1], first[2], first[3], 1, 3));
		assertTrue("second covers the shared cell", KittyPlacementGeometry.coversCell(
			second[0], second[1], second[2], second[3], 1, 3));
		assertTrue("later placement is the topmost one", KittyPlacementGeometry.comparePlacementOrder(
			0, 2L, 0, 1L) > 0);
		assertFalse("a cell outside both rectangles", KittyPlacementGeometry.coversCell(
			first[0], first[1], first[2], first[3], 3, 0)
			|| KittyPlacementGeometry.coversCell(second[0], second[1], second[2], second[3], 3, 0));
	}

	public void testCoversCellDoesNotOverflowWithHugeRectangles() {
		assertTrue("c/r larger than the screen cover any visible cell", KittyPlacementGeometry.coversCell(
			0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1000, 500));
		assertTrue("origin+height must not wrap around", KittyPlacementGeometry.coversCell(
			Integer.MAX_VALUE - 1, 0, 4, 4, Integer.MAX_VALUE, 0));
	}

	// -------------------------------------------------------------------- RF-20, RF-21

	/** Placement with no crop, for the ordering tests that only need geometry + order. */
	private static TerminalImagePlacement placement(int key, int registryId, int z, long creationSeq,
			int startRow, int startCol, int cellsW, int cellsH) {
		return new TerminalImagePlacement(key, registryId, 0, startRow, startCol, cellsW, cellsH,
			0, 0, 0, 0, z, creationSeq, false);
	}

	public void testTopmostKeyAtWithoutCoverageIsZero() {
		final List<TerminalImagePlacement> placements = Arrays.asList(placement(1, 1, 0, 1, 2, 3, 4, 5));
		assertEquals("cell above the rectangle", 0,
			KittyPlacementGeometry.topmostKeyAt(placements, 1, 3));
		assertEquals("cell past the last column", 0,
			KittyPlacementGeometry.topmostKeyAt(placements, 2, 7));
		assertEquals("empty list", 0, KittyPlacementGeometry.topmostKeyAt(
			Arrays.<TerminalImagePlacement>asList(), 2, 3));
	}

	public void testTopmostKeyAtIgnoresNullAndInvisibleEntries() {
		final List<TerminalImagePlacement> placements = Arrays.asList(
			null, placement(1, 1, 0, 1, 0, 0, 0, 0), placement(2, 1, 0, 2, 5, 5, 2, 2));
		assertEquals("null and zero-sized entries are skipped", 2,
			KittyPlacementGeometry.topmostKeyAt(placements, 5, 5));
	}

	public void testTopmostKeyAtOverlapPrefersHigherZ() {
		// RF-20/RF-21: lower z paints first, so the higher z ends up on top.
		final List<TerminalImagePlacement> placements = Arrays.asList(
			placement(1, 1, 5, 1, 0, 0, 4, 2),
			placement(2, 1, -1, 2, 1, 2, 4, 2));
		assertEquals("z=5 beats z=-1 on the shared cell", 1,
			KittyPlacementGeometry.topmostKeyAt(placements, 1, 3));
	}

	public void testTopmostKeyAtTiePrefersLaterCreation() {
		// RF-20 tiebreak: equal z is decided by the creation sequence.
		assertEquals("later creation wins", 2, KittyPlacementGeometry.topmostKeyAt(Arrays.asList(
			placement(1, 1, 0, 1, 0, 0, 4, 2),
			placement(2, 1, 0, 2, 1, 2, 4, 2)), 1, 3));
		assertEquals("earlier creation loses", 1, KittyPlacementGeometry.topmostKeyAt(Arrays.asList(
			placement(1, 1, 0, 7, 0, 0, 4, 2),
			placement(2, 1, 0, 3, 1, 2, 4, 2)), 1, 3));
	}

	// -------------------------------------------------------------------------- RF-37

	public void testOldestPlacementIndexForImageWithoutMatchesIsMinusOne() {
		assertEquals("empty list", -1, KittyPlacementGeometry.oldestPlacementIndexForImage(
			Arrays.<TerminalImagePlacement>asList(), 1));
		assertEquals("no placement of that image", -1,
			KittyPlacementGeometry.oldestPlacementIndexForImage(
				Arrays.asList(placement(1, 1, 0, 1, 0, 0, 2, 2)), 9));
	}

	public void testOldestPlacementIndexForImagePicksTheSmallestSequence() {
		// RF-37: the cap evicts the oldest placement of the image, whatever its list index.
		final List<TerminalImagePlacement> placements = Arrays.asList(
			null,
			placement(1, 5, 0, 9, 0, 0, 2, 2),
			placement(2, 7, 0, 1, 0, 0, 2, 2),
			placement(3, 5, 0, 4, 2, 0, 2, 2),
			placement(4, 5, 0, 12, 4, 0, 2, 2));
		assertEquals("oldest of image 5, ignoring other images and nulls", 3,
			KittyPlacementGeometry.oldestPlacementIndexForImage(placements, 5));
	}

	public void testOldestPlacementIndexForImageWith257Placements() {
		// RF-37: 257 placements of one image => the very first one is the oldest.
		final List<TerminalImagePlacement> placements = new java.util.ArrayList<>();
		for (int i = 0; i < 257; i++) {
			placements.add(placement(i + 1, 3, 0, i + 1, i % 10, 0, 1, 1));
		}
		assertEquals("the oldest of 257 is the first", 0,
			KittyPlacementGeometry.oldestPlacementIndexForImage(placements, 3));
	}
}
