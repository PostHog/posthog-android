package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public enum class RRMouseInteraction(public val value: Int) {
    MouseUp(0),
    MouseDown(1),
    Click(2),
    ContextMenu(3),
    DblClick(4),
    Focus(5),
    Blur(6),
    TouchStart(7),
    TouchMoveDeparted(8),
    TouchEnd(9),
    TouchCancel(10),
    ;

    public companion object {
        public fun fromValue(value: Int): RRMouseInteraction? {
            return when (value) {
                0 -> MouseUp
                1 -> MouseDown
                2 -> Click
                3 -> ContextMenu
                4 -> DblClick
                5 -> Focus
                6 -> Blur
                7 -> TouchStart
                8 -> TouchMoveDeparted
                9 -> TouchEnd
                10 -> TouchCancel
                else -> null
            }
        }
    }
}
