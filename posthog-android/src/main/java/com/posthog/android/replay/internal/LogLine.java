// borrowed from https://cs.android.com/android/platform/superproject/main/+/main:development/tools/bugreport/src/com/android/bugreport/logcat/LogLine.java;bpv=0;bpt=0

package com.posthog.android.replay.internal;

import com.posthog.PostHogInternal;

import java.util.GregorianCalendar;

/**
 * A log line.
 */
@PostHogInternal
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
