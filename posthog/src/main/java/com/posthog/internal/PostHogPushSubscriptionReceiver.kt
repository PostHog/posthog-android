package com.posthog.internal

import com.posthog.PostHogInternal

/** Receives automatically fetched tokens without refreshing an already delivered subscription. */
@PostHogInternal
public interface PostHogPushSubscriptionReceiver {
    public fun registerPushNotificationTokenIfNeeded(
        deviceToken: String,
        appId: String,
    )
}
