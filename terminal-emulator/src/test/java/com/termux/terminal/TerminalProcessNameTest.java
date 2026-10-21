package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract of the process identity exposed to CLI tools that inspect {@code /proc/<pid>/comm}
 * (fastfetch, neofetch and the like walk that file up the parent chain to find the terminal app).
 *
 * <p>Android truncates {@code comm} to {@code TASK_COMM_LEN - 1} characters for the app's package
 * name, so the identity must stay within that limit or it would be chopped again (see spec 005:
 * "com.estrin217.terminal" surfaced as "rin217.terminal").
 */
public class TerminalProcessNameTest {

	/** Linux TASK_COMM_LEN (16) minus the terminating NUL. */
	private static final int COMM_MAX_LENGTH = 15;

	@Test
	public void processIdentityMatchesAppLabel() {
		assertEquals("Terminal", TerminalProcessName.PROCESS_IDENTITY);
	}

	@Test
	public void processIdentityFitsCommLimit() {
		assertTrue("identity must fit comm (max 15 chars)",
			TerminalProcessName.PROCESS_IDENTITY.length() <= COMM_MAX_LENGTH);
	}

	@Test
	public void processIdentityHasNoPathSeparator() {
		// comm is a bare task name; a path separator would signal a mixing of concepts
		assertFalse(TerminalProcessName.PROCESS_IDENTITY.contains("/"));
	}
}
