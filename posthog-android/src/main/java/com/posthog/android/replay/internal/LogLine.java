package com.posthog.android.replay.internal;

import java.util.GregorianCalendar;

/**
 * A log line.
 */
public class LogLine {
    /**
     * The timestamp of the event. In UTC even though the device might not have been.
     */
    public GregorianCalendar time;

    /**
     * The log level. One of EWIDV.
     */
    public String level;

    /**
     * The log tag.
     */
    public String tag;

    public String text;
}
