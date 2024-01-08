package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public enum class RRIncrementalSource(public val value: Int) {
    Mutation(0),
    MouseMove(1),
    MouseInteraction(2),
    Scroll(3),
    ViewportResize(4),
    Input(5),
    TouchMove(6),
    MediaInteraction(7),
    StyleSheetRule(8),
    CanvasMutation(9),
    Font(10),
    Log(11),
    Drag(12),
    StyleDeclaration(13),
    Selection(14),
    AdoptedStyleSheet(15),
    CustomElement(16),
    ;

    public companion object {
        public fun fromValue(value: Int): RRIncrementalSource? {
            return when (value) {
                0 -> Mutation
                1 -> MouseMove
                2 -> MouseInteraction
                3 -> Scroll
                4 -> ViewportResize
                5 -> Input
                6 -> TouchMove
                7 -> MediaInteraction
                8 -> StyleSheetRule
                9 -> CanvasMutation
                10 -> Font
                11 -> Log
                12 -> Drag
                13 -> StyleDeclaration
                14 -> Selection
                15 -> AdoptedStyleSheet
                16 -> CustomElement
                else -> null
            }
        }
    }
}
