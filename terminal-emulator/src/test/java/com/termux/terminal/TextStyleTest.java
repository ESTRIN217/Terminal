package com.termux.terminal;

import junit.framework.TestCase;

public class TextStyleTest extends TestCase {

	private static final int[] ALL_EFFECTS = new int[]{0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
			TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.CHARACTER_ATTRIBUTE_BLINK, TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
			TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE, TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH, TextStyle.CHARACTER_ATTRIBUTE_PROTECTED,
			TextStyle.CHARACTER_ATTRIBUTE_DIM};

	public void testEncodingSingle() {
		for (int fx : ALL_EFFECTS) {
			for (int fg = 0; fg < TextStyle.NUM_INDEXED_COLORS; fg++) {
				for (int bg = 0; bg < TextStyle.NUM_INDEXED_COLORS; bg++) {
					long encoded = TextStyle.encode(fg, bg, fx);
					assertEquals(fg, TextStyle.decodeForeColor(encoded));
					assertEquals(bg, TextStyle.decodeBackColor(encoded));
					assertEquals(fx, TextStyle.decodeEffect(encoded));
				}
			}
		}
	}

	public void testEncoding24Bit() {
		int[] values = {255, 240, 127, 1, 0};
		for (int red : values) {
			for (int green : values) {
				for (int blue : values) {
					int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
					long encoded = TextStyle.encode(argb, 0, 0);
					assertEquals(argb, TextStyle.decodeForeColor(encoded));
					encoded = TextStyle.encode(0, argb, 0);
					assertEquals(argb, TextStyle.decodeBackColor(encoded));
				}
			}
		}
	}


	public void testEncodingCombinations() {
		for (int f1 : ALL_EFFECTS) {
			for (int f2 : ALL_EFFECTS) {
				int combined = f1 | f2;
				assertEquals(combined, TextStyle.decodeEffect(TextStyle.encode(0, 0, combined)));
			}
		}
	}

	public void testEncodingStrikeThrough() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
	}

	public void testEncodingProtected() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertEquals(0, (TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED));
		encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH | TextStyle.CHARACTER_ATTRIBUTE_PROTECTED);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) != 0);
	}

	/** Palette with recognizable values at the indices the tests below rely on. */
	private static int[] makePalette() {
		int[] palette = new int[TextStyle.NUM_INDEXED_COLORS];
		for (int i = 0; i < palette.length; i++) palette[i] = 0xFF000000 | (i << 16);
		palette[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF112233;
		palette[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFF445566;
		palette[1] = 0xFF0000FF; // blue
		palette[7] = 0xFFFFFFFF; // white
		palette[9] = 0xFFAAAAAA; // bright variant of index 1
		return palette;
	}

	/** Default cell → the palette background (the color the frame/view fills with). */
	public void testEffectiveBackgroundDefaultCell() {
		int[] palette = makePalette();
		long style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND, 0);
		assertEquals(0xFF112233, TextStyle.effectiveBackgroundColor(style, palette, false));
	}

	/** Indexed backgrounds resolve through the palette; 24-bit backgrounds pass through. */
	public void testEffectiveBackgroundIndexedAndTruecolor() {
		int[] palette = makePalette();
		long style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 1, 0);
		assertEquals(0xFF0000FF, TextStyle.effectiveBackgroundColor(style, palette, false));
		style = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, 0xFFABCDEF, 0);
		assertEquals(0xFFABCDEF, TextStyle.effectiveBackgroundColor(style, palette, false));
	}

	/** Inverse cell or reverse-video flag swap bg↔fg; both set swap twice (no change). */
	public void testEffectiveBackgroundInvertsWithInverseOrReverse() {
		int[] palette = makePalette();
		long style = TextStyle.encode(7, 1, TextStyle.CHARACTER_ATTRIBUTE_INVERSE);
		assertEquals(0xFFFFFFFF, TextStyle.effectiveBackgroundColor(style, palette, false));
		style = TextStyle.encode(7, 1, 0);
		assertEquals(0xFFFFFFFF, TextStyle.effectiveBackgroundColor(style, palette, true));
		style = TextStyle.encode(7, 1, TextStyle.CHARACTER_ATTRIBUTE_INVERSE);
		assertEquals(0xFF0000FF, TextStyle.effectiveBackgroundColor(style, palette, true));
	}

	/** Bold foregrounds brighten (0–7 → +8) before a reverse-video swap, like drawTextRun. */
	public void testEffectiveBackgroundBoldBrightWhenInverted() {
		int[] palette = makePalette();
		long style = TextStyle.encode(1, 7,
				TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_INVERSE);
		assertEquals(0xFFAAAAAA, TextStyle.effectiveBackgroundColor(style, palette, false));
	}

}
