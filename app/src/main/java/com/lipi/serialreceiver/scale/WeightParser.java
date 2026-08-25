package com.lipi.serialreceiver.scale;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw ASCII frames from the ESSAE AX-620 indicator into a numeric weight (kg).
 *
 * The AX-620, like most Essae indicators, streams frames such as:
 *   "ST,GS,+   25.00,kg\r\n"   (stable, gross weight)
 *   "US,GS,+   25.05,kg\r\n"   (unstable / still settling)
 *   or a plain "   25.00 kg\r\n" depending on the configured output format.
 *
 * Rather than hardcode one exact byte layout (which varies by indicator's serial-output
 * setting — F1/F2/continuous mode, etc.), this parser:
 *   1) Looks for a leading stability flag (ST = stable, US = unstable) if present.
 *   2) Extracts the first signed decimal number in the frame as the weight.
 *   3) Reports whether the reading is "stable" so the UI can wait for a settled value
 *      before comparing against the expected inventory weight.
 *
 * If your indicator's exact frame format differs, adjust FRAME_PATTERN / STABLE_PATTERN
 * below — the rest of the app only depends on the ParsedWeight object this class returns.
 */
public class WeightParser {

    // Matches the first signed/unsigned decimal number in the line, e.g. "+ 25.00", "-0.5", "25"
    private static final Pattern NUMBER_PATTERN = Pattern.compile("([+-]?\\s?\\d+(?:\\.\\d+)?)");

    // Common Essae stability markers
    private static final Pattern STABLE_PATTERN = Pattern.compile("\\bST\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSTABLE_PATTERN = Pattern.compile("\\bUS\\b", Pattern.CASE_INSENSITIVE);

    public static class ParsedWeight {
        public final double weightKg;
        public final boolean stable;   // true if scale reported a settled/stable reading
        public final String rawLine;

        public ParsedWeight(double weightKg, boolean stable, String rawLine) {
            this.weightKg = weightKg;
            this.stable = stable;
            this.rawLine = rawLine;
        }
    }

    /**
     * Parses one line of scale output. Returns null if no numeric weight could be found
     * (e.g. partial/garbled frame — caller should just wait for the next line).
     */
    public static ParsedWeight parse(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        Matcher numMatcher = NUMBER_PATTERN.matcher(trimmed);
        if (!numMatcher.find()) return null;

        String numStr = numMatcher.group(1).replace(" ", "");
        double weight;
        try {
            weight = Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return null;
        }

        boolean stable = STABLE_PATTERN.matcher(trimmed).find();
        boolean unstable = UNSTABLE_PATTERN.matcher(trimmed).find();
        // If neither flag is present, treat as stable (many indicators only send a
        // reading once it has settled, i.e. "print on demand" / "auto print" mode).
        boolean isStable = stable || !unstable;

        return new ParsedWeight(weight, isStable, trimmed);
    }
}
