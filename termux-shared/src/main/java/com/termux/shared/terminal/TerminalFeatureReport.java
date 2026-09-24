package com.termux.shared.terminal;

/**
 * iTerm2 Feature Reporting ({@code TERM_FEATURES} / {@code OSC 1337;Capabilities}).
 * <p>
 * Codes match <a href="https://iterm2.com/feature-reporting">iterm2.com/feature-reporting</a>:
 * a boolean is present or absent; a UInt is {@code Name} followed by decimal digits.
 * Unknown codes must be ignored by applications.
 * <p>
 * Kitty graphics are <em>not</em> advertised here — apps probe with APC
 * {@code ESC _ G a=q … ESC \}.
 */
public final class TerminalFeatureReport {

    private static final String LOG_TAG = "TerminalFeatureReport";

    /** 24BIT: compatibility + full RGB SGR ({@code CSI 38;2;…} and colon form). */
    public static final String CODE_24BIT = "T3";
    /** BRACKETED_PASTE (DECSET 2004). */
    public static final String CODE_BRACKETED_PASTE = "B";
    /** MOUSE (DECSET 1000/1002/1006). */
    public static final String CODE_MOUSE = "M";
    /** HYPERLINKS (OSC 8). */
    public static final String CODE_HYPERLINKS = "H";
    /** FILE (OSC 1337 File inline images). */
    public static final String CODE_FILE = "F";

    private TerminalFeatureReport() {
    }

    /**
     * Build a feature string from kill-switch state.
     *
     * @param imagesEnabled    {@code terminal_images} — advertise {@code F} when true
     * @param hyperlinksEnabled {@code terminal_hyperlinks} — advertise {@code H} when true
     * @return e.g. {@code T3BMHF} or {@code T3BM} when both features are off
     */
    public static String buildFeatureString(boolean imagesEnabled, boolean hyperlinksEnabled) {
        final StringBuilder sb = new StringBuilder(8);
        sb.append(CODE_24BIT);
        sb.append(CODE_BRACKETED_PASTE);
        sb.append(CODE_MOUSE);
        if (hyperlinksEnabled) sb.append(CODE_HYPERLINKS);
        if (imagesEnabled) sb.append(CODE_FILE);
        return sb.toString();
    }
}
