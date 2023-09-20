package com.posthog.internal

import com.posthog.PostHogPreferences
import java.util.UUID

// TODO: think about stateless about posthog-java (js-lite)
internal class PostHogSessionManager(private val preferences: PostHogPreferences) {

    // TODO: thread safety

    private val anonymousKey = "posthog.anonymousId"
    private val distinctIdKey = "posthog.distinctId"

    var anonymousId: String
        get() {
            var anonymousId = preferences.getValue(anonymousKey) as? String

            if (anonymousId == null) {
                anonymousId = UUID.randomUUID().toString()
                this.anonymousId = anonymousId
            }
            return anonymousId
        }
        set(value) {
            preferences.setValue(anonymousKey, value)
        }

    var distinctId: String
        get() {
            return preferences.getValue(distinctIdKey, defaultValue = anonymousId) as? String ?: anonymousId
        }
        set(value) {
            preferences.setValue(distinctIdKey, value)
        }
}
