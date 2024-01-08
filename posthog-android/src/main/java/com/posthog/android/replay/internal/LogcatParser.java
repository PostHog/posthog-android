// borrowed from https://cs.android.com/android/platform/superproject/main/+/main:development/tools/bugreport/src/com/android/bugreport/logcat/LogcatParser.java;bpv=0;bpt=0

package com.posthog.android.replay.internal;

import com.posthog.PostHogInternal;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a stream of text as a logcat.
 */
@PostHogInternal
public class LogcatParser {

    /**
     * UTC Time Zone.
     */
    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public static final String DATE_TIME_MS_PATTERN
            = "(?:(\\d\\d\\d\\d)-)?(\\d\\d)-(\\d\\d)\\s+(\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d\\d\\d)";

    public static final Pattern BUFFER_BEGIN_RE = Pattern.compile(
            "--------- beginning of (.*)");
    private static final Pattern LOG_LINE_RE = Pattern.compile(
            "(" + DATE_TIME_MS_PATTERN
                    + "\\s+(\\d+)\\s+(\\d+)\\s+(.)\\s+)(.*?):\\s(.*)");

    private final Matcher mBufferBeginRe = BUFFER_BEGIN_RE.matcher("");
    private final Matcher mLogLineRe = LOG_LINE_RE.matcher("");

    /**
     * Parse the logcat lines, returning a Logcat object.
     */
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

                ll.time = parseCalendar(m, 2, true);
                char level = m.group(11).charAt(0);

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
                ll.tag = m.group(12);
                ll.text = m.group(13);

                result = ll;
            }
        } catch (Throwable e) {
            // Ignore
        }

        return result;
    }

    /**
     * Returns the matcher if it matches the text, null otherwise.
     */
    private static Matcher match(Matcher matcher, String text) {
        matcher.reset(text);
        if (matcher.matches()) {
            return matcher;
        } else {
            return null;
        }
    }

    /**
     * Gets the date time groups from the matcher and returns a GregorianCalendar.
     * The year is optional.
     *
     * @param matcher a matcher
     * @param startGroup the index of the first group to use
     * @param milliseconds whether to expect the millisecond group.
     *
     * @see #DATE_TIME_MS_PATTERN
     */
    private static GregorianCalendar parseCalendar(Matcher matcher, int startGroup,
                                                  boolean milliseconds) {
        final GregorianCalendar result = new GregorianCalendar();

        if (matcher.group(startGroup+0) != null) {
            result.set(Calendar.YEAR, Integer.parseInt(matcher.group(startGroup + 0)));
        }
        // -1 because of https://stackoverflow.com/questions/344380/why-is-january-month-0-in-java-calendar
        result.set(Calendar.MONTH, Integer.parseInt(matcher.group(startGroup + 1)) - 1);
        result.set(Calendar.DAY_OF_MONTH, Integer.parseInt(matcher.group(startGroup + 2)));
        result.set(Calendar.HOUR_OF_DAY, Integer.parseInt(matcher.group(startGroup + 3)));
        result.set(Calendar.MINUTE, Integer.parseInt(matcher.group(startGroup + 4)));
        result.set(Calendar.SECOND, Integer.parseInt(matcher.group(startGroup + 5)));
        if (milliseconds) {
            result.set(Calendar.MILLISECOND, Integer.parseInt(matcher.group(startGroup + 6)));
        }

        return result;
    }

}
