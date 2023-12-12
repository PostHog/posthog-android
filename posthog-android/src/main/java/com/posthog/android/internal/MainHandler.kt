package com.posthog.android.internal

import android.os.Handler
import android.os.Looper
import com.posthog.PostHogInternal

@PostHogInternal
public class MainHandler(public val mainLooper: Looper = Looper.getMainLooper()) {
    public val handler: Handler = Handler(mainLooper)
}
