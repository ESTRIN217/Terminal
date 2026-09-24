package com.termux.terminal;

/**
 * Autodetection: XTVERSION, OSC 1337 Feature Reporting (Capabilities),
 * and feature-string contents gated by image/hyperlink kill-switches.
 */
public class FeatureReportTest extends TerminalTestCase {

	public void testXtVersionZeroRepliesDcs() {
		withTerminalSized(2, 2);
		enterString("\033[>0q");
		final String reply = mOutput.getOutputAndClear();
		assertEquals("\033P> |Terminal(2.0.0)\033\\", reply);
		assertLinesAre("  ", "  ");
	}

	public void testXtVersionBareQueryReplies() {
		withTerminalSized(2, 2);
		enterString("\033[>q");
		final String reply = mOutput.getOutputAndClear();
		assertTrue("expected XTVERSION DCS, got: " + reply,
			reply.startsWith("\033P> |Terminal("));
		assertTrue(reply.endsWith("\033\\"));
	}

	public void testXtVersionCustomVersion() {
		withTerminalSized(2, 2);
		mTerminal.setXtVersion("9.9.9-test");
		enterString("\033[>0q");
		assertEquals("\033P> |Terminal(9.9.9-test)\033\\", mOutput.getOutputAndClear());
	}

	public void testXtVersionDoesNotStealDa2() {
		withTerminalSized(2, 2);
		enterString("\033[>c");
		assertEquals("\033[>41;320;0c", mOutput.getOutputAndClear());
	}

	public void testCapabilitiesQueryIncludesFileAndHyperlinks() {
		withTerminalSized(2, 2);
		final String features = mTerminal.buildFeatureString();
		assertTrue(features.contains("T3"));
		assertTrue(features.contains("B"));
		assertTrue(features.contains("M"));
		assertTrue(features.contains("H"));
		assertTrue(features.contains("F"));
		assertFalse(features.contains("Sx"));

		enterString("\033]1337;Capabilities\033\\");
		final String reply = mOutput.getOutputAndClear();
		assertEquals("\033]1337;Capabilities=" + features + "\033\\", reply);
	}

	public void testCapabilitiesOmitsFileWhenImagesDisabled() {
		withTerminalSized(2, 2);
		mTerminal.setTerminalImagesEnabled(false);
		enterString("\033]1337;Capabilities\007");
		final String reply = mOutput.getOutputAndClear();
		assertTrue(reply, reply.contains("Capabilities="));
		assertFalse("FILE must be absent when kill-switch is off: " + reply, reply.contains("F"));
		assertTrue(reply.contains("H"));

		// Query still works; kitty a=q still OK (not gated by kill-switch).
		enterString("\033_Gi=31,a=q\033\\");
		assertTrue(mOutput.getOutputAndClear().contains("OK"));
	}

	public void testCapabilitiesOmitsHyperlinksWhenDisabled() {
		withTerminalSized(2, 2);
		mTerminal.setHyperlinksEnabled(false);
		enterString("\033]1337;Capabilities\033\\");
		final String reply = mOutput.getOutputAndClear();
		assertFalse("H must be absent: " + reply, reply.endsWith("=\033\\") && reply.contains("H="));
		// Parse as FeatureString between = and ST
		final int eq = reply.indexOf('=');
		assertTrue(eq > 0);
		final String body = reply.substring(eq + 1, reply.length() - 2);
		assertFalse(body.contains("H"));
		assertTrue(body.contains("F"));
	}

	public void testCapabilitiesDoesNotPaint() {
		withTerminalSized(4, 2);
		enterString("\033]1337;Capabilities\033\\");
		mOutput.getOutputAndClear();
		assertLinesAre("    ", "    ");
		assertNull(mTerminal.getImageDataAt(0, 0));
	}

	public void testFeatureStringMatchesSharedBuilder() {
		withTerminalSized(2, 2);
		// Keep TerminalEmulator.buildFeatureString aligned with TerminalFeatureReport codes
		// for the default (both ON) case: T3 + B + M + H + F.
		assertEquals("T3BMHF", mTerminal.buildFeatureString());
		mTerminal.setTerminalImagesEnabled(false);
		assertEquals("T3BMH", mTerminal.buildFeatureString());
		mTerminal.setTerminalImagesEnabled(true);
		mTerminal.setHyperlinksEnabled(false);
		assertEquals("T3BMF", mTerminal.buildFeatureString());
		mTerminal.setHyperlinksEnabled(false);
		mTerminal.setTerminalImagesEnabled(false);
		assertEquals("T3BM", mTerminal.buildFeatureString());
	}
}
