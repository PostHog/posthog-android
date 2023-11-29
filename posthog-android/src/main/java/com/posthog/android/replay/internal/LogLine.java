package com.posthog.android.replay.internal;

import java.util.GregorianCalendar;

/**
 * A log line.
 */
public class LogLine {

//    /**
//     * The raw text of the log.
//     */
//    public String rawText;

//    /**
//     * If this is set, this line is the beginning of a log buffer. All the following
//     * fields will be null / unset.
//     */
//    public String bufferBegin;

//    /**
//     * The raw text of everything up to the tag.
//     */
//    public String header;

    /**
     * The timestamp of the event. In UTC even though the device might not have been.
     */
    public GregorianCalendar time;

//    /**
//     * The process that emitted the log.
//     */
//    public int pid = -1;
//
//    /**
//     * The thread that emitted the log.
//     */
//    public int tid = -1;

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