package com.termux.terminal;

/**
 * Identity this app exposes to the host system for CLI tools that inspect {@code /proc/<pid>/comm}
 * (fastfetch, neofetch and the like walk {@code comm} up the parent chain to identify the terminal app).
 *
 * <p>Android truncates the package name {@code comm} to its last {@value #COMM_MAX_LENGTH} characters,
 * which surfaced this app as the meaningless "rin217.terminal"; a short identity avoids that. The
 * identity must be applied on the app's main thread during {@code Application.onCreate} so that it
 * is visible at the process level (the process {@code comm} is the {@code comm} of its main task)
 * and inherited by the terminal sessions forked afterwards; shells and proot later rename their own
 * {@code comm} on {@code execve}, which does not affect the search done by CLI tools.</p>
 */
public final class TerminalProcessName {

	private static final String LOG_TAG = "TerminalProcessName";

	/** Linux {@code TASK_COMM_LEN} (16) minus the terminating NUL. */
	public static final int COMM_MAX_LENGTH = 15;

	/** Identity shown to CLI tools; matches the app label and the XTVERSION report. */
	public static final String PROCESS_IDENTITY = "Terminal";

	private TerminalProcessName() { }

	/**
	 * Apply {@link #PROCESS_IDENTITY} to the calling process via {@code prctl(PR_SET_NAME)}.
	 * <p>
	 * Call on the app's main thread ({@code Application.onCreate}); a longer name is truncated
	 * by the kernel to its first {@value #COMM_MAX_LENGTH} characters, but callers should never
	 * need more.
	 *
	 * @return {@code true} when the kernel accepted the process name; {@code false} on failure
	 *         (SECCOMP/SELinux policy, error) with the previous identity left untouched, so
	 *         callers can continue without blocking startup.
	 */
	public static boolean applyProcessIdentity() {
		try {
			return JNI.setProcessName(PROCESS_IDENTITY);
		} catch (Throwable throwable) {
			// Logging only: failure to brand the process is cosmetic, not fatal.
			Logger.logWarn(null, LOG_TAG, "Failed to set process identity to \""
				+ PROCESS_IDENTITY + "\" on the calling process: "
				+ Logger.getMessageAndStackTraceString(null, throwable));
			return false;
		}
	}

}
