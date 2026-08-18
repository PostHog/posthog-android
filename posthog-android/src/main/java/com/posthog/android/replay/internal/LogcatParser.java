// borrowed from https://cs.android.com/android/platform/superproject/main/+/main:development/tools/bugreport/src/com/android/bugreport/logcat/LogcatParser.java;bpv=0;bpt=0

package com.posthog.android.replay.internal;

import com.posthog.PostHogInternal;

import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses logcat output formatted with {@code -v epoch}. */
@PostHogInternal
public class LogcatParser {

    /** UTC Time Zone. */
    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** Legacy threadtime pattern retained for API compatibility. */
    public static final String DATE_TIME_MS_PATTERN =
            "(?:(\\d\\d\\d\\d)-)?(\\d\\d)-(\\d\\d)\\s+(\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d\\d\\d)";

    private static final String EPOCH_TIME_PATTERN = "(\\d+)\\.(\\d{1,9})";

    public static final Pattern BUFFER_BEGIN_RE = Pattern.compile("--------- beginning of (.*)");

    private static final Pattern LOG_LINE_RE =
            Pattern.compile(
                    "\\s*"
                            + EPOCH_TIME_PATTERN
                            + "\\s+(\\d+)\\s+(\\d+)\\s+(.)\\s+(.*?):\\s(.*)");

    private final Matcher mBufferBeginRe = BUFFER_BEGIN_RE.matcher("");
    private final Matcher mLogLineRe = LOG_LINE_RE.matcher("");

    /** Parse a logcat epoch line, returning a LogLine object. */
    public LogLine parse(String text) {
        LogLine result = null;
        try {
            Matcher m;

            if (match(mBufferBeginRe, text) != null) {
                // Beginning of buffer marker
                return null;
            } else if ((m = match(mLogLineRe, text)) != null) {
                // Matched line
                final LogLine ll = new LogLine();

                ll.time = parseEpochCalendar(m);
                char level = m.group(5).charAt(0);

                switch (level) {
                    case 'I':
                        ll.level = "info";
                        break;
                    case 'W':
                        ll.level = "warn";
                        break;
                    case 'F':
                    case 'E':
                        ll.level = "error";
                        break;
                    case 'V':
                    case 'D':
                    default:
                        ll.level = "debug";
                        break;
                }
                ll.tag = m.group(6);
                ll.text = m.group(7);

                result = ll;
            }
        } catch (Throwable e) {
            // Ignore
        }

        return result;
    }

    /** Returns the matcher if it matches the text, null otherwise. */
    private static Matcher match(Matcher matcher, String text) {
        matcher.reset(text);
        if (matcher.matches()) {
            return matcher;
        } else {
            return null;
        }
    }

    /** Converts epoch seconds and their fractional component directly to a UTC instant. */
    private static GregorianCalendar parseEpochCalendar(Matcher matcher) {
        final long seconds = Long.parseLong(matcher.group(1));
        final String fraction = matcher.group(2);
        final int milliseconds =
                Integer.parseInt((fraction + "000").substring(0, 3));
        final GregorianCalendar result = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        result.setTimeInMillis(seconds * 1000L + milliseconds);
        return result;
    }
}
