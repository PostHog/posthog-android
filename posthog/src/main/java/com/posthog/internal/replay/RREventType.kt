package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public enum class RREventType(public val value: Int) {
    DomContentLoaded(0),
    Load(1),
    FullSnapshot(2),
    IncrementalSnapshot(3),
    Meta(4),
    Custom(5),
    Plugin(6),
    ;

    public companion object {
        public fun fromValue(value: Int): RREventType? {
            return when (value) {
                0 -> DomContentLoaded
                1 -> Load
                2 -> FullSnapshot
                3 -> IncrementalSnapshot
                4 -> Meta
                5 -> Custom
                6 -> Plugin
                else -> null
            }
        }
    }
}
