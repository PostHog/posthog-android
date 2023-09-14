package com.posthog.internal

import java.util.UUID

// TODO: think about stateless about posthog-java (js-lite)
internal class PostHogSessionManager(private val storage: PostHogStorage) {

    // TODO: thread safety

    private val anonymousKey = "posthog.anonymousId"
    private val distinctIdKey = "posthog.distinctId"

    var anonymousId: String
        get() {
            var anonymousId = storage.getString(anonymousKey)

            if (anonymousId == null) {
                anonymousId = UUID.randomUUID().toString()
                this.anonymousId = anonymousId
            }
            return anonymousId
        }
        set(value) {
            storage.setString(anonymousKey, value)
        }

    var distinctId: String
        get() {
            return storage.getString(distinctIdKey, defaultValue = anonymousId) ?: anonymousId
        }
        set(value) {
            storage.setString(distinctIdKey, value)
        }
}
