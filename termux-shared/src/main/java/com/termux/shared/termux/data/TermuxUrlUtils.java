package com.termux.shared.termux.data;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.termux.terminal.TerminalUrlScanner;

public class TermuxUrlUtils {

    /**
     * The recognizer was moved to the terminal-emulator module (Spec 003) so the terminal
     * renderers, which cannot depend on this shared module, use the same single pattern; this
     * class keeps its public API and just delegates, so the dialog behavior never changes.
     *
     * @return the same pattern the terminal URL scanner detects plain links with
     */
    public static Pattern getUrlMatchRegex() {
        return TerminalUrlScanner.getUrlPattern();
    }

    public static LinkedHashSet<CharSequence> extractUrls(String text) {
        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = getUrlMatchRegex().matcher(text);

        while (matcher.find()) {
            String url = matcher.group();
            urlSet.add(url);
        }

        return urlSet;
    }

}
