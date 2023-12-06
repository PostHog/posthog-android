package com.posthog.internal

import com.posthog.PostHog
import com.posthog.PostHogInternal

@PostHogInternal
public open class RREvent(
    public val type: RREventType,
    public val data: Any? = null,
    public val timestamp: Long = System.currentTimeMillis(),
)

// internal enum class RRNodeType(val value: Int) {
//    Document(0),
//    DocumentType(1),
//    Element(2),
//    Text(3),
//    CDATA(4),
//    Comment(5),
// }

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
        public fun fromValue(value: Int): RREventType {
            return when (value) {
                0 -> DomContentLoaded
                1 -> Load
                2 -> FullSnapshot
                3 -> IncrementalSnapshot
                4 -> Meta
                5 -> Custom
                6 -> Plugin
                else -> throw IllegalArgumentException("Unknown value $value")
            }
        }
    }
}

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
        public fun fromValue(value: Int): RRIncrementalSource {
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
                else -> throw IllegalArgumentException("Unknown value $value")
            }
        }
    }
}

@PostHogInternal
public class RRDomContentLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.DomContentLoaded,
    timestamp = timestamp,
)

@PostHogInternal
public class RRLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.Load,
    timestamp = timestamp,
)

@PostHogInternal
public class RRFullSnapshotEvent(
    wireframes: List<RRWireframe>,
    initialOffsetTop: Int,
    initialOffsetLeft: Int,
    timestamp: Long,
) : RREvent(
    type = RREventType.FullSnapshot,
    data = mapOf(
        "wireframes" to wireframes,
        "initialOffset" to mapOf(
            "top" to initialOffsetTop,
            "left" to initialOffsetLeft,
        ),
    ),
    timestamp = timestamp,
)

@PostHogInternal
public class RRIncrementalSnapshotEvent(
    mutationData: RRIncrementalMutationData? = null,
    timestamp: Long,
) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = mutationData,
    timestamp = timestamp,
)

@PostHogInternal
public class RRIncrementalMouseInteractionEvent(
    mouseInteractionData: RRIncrementalMouseInteractionData? = null,
    timestamp: Long,
) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = mouseInteractionData,
    timestamp = timestamp,
)

@PostHogInternal
public class RRAddedNode(
    public val wireframe: RRWireframe,
    public val parentId: Int? = null,
)

@PostHogInternal
public class RRRemovedNode(
    public val id: Int,
    public val parentId: Int? = null,
)

@PostHogInternal
public class RRIncrementalMutationData(
    public val adds: List<RRAddedNode>? = null,
    public val removes: List<RRRemovedNode>? = null,
    public val source: RRIncrementalSource = RRIncrementalSource.Mutation,
    // TODO: do we need updates?
)

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
    TouchMoveDeparted(8), // we will start a separate observer for touch move event
    TouchEnd(9),
    TouchCancel(10),
    ;

    public companion object {
        public fun fromValue(value: Int): RRMouseInteraction {
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
                else -> throw IllegalArgumentException("Unknown value $value")
            }
        }
    }
}

@PostHogInternal
public class RRIncrementalMouseInteractionData(
    public val id: Int,
    public val type: RRMouseInteraction,
    public val x: Int,
    public val y: Int,
    public val source: RRIncrementalSource = RRIncrementalSource.MouseInteraction,
    public val pointerType: Int = 2, // always Touch
)

@PostHogInternal
public class RRMetaEvent(width: Int, height: Int, timestamp: Long, href: String) : RREvent(
    type = RREventType.Meta,
    data = mapOf(
        "href" to href,
        "width" to width,
        "height" to height,
    ),
    timestamp = timestamp,
)

@PostHogInternal
public class RRCustomEvent(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf(
        "tag" to tag,
        "payload" to payload,
    ),
)

@PostHogInternal
public class RRPluginEvent(plugin: String, payload: Map<String, Any>, timestamp: Long) : RREvent(
    type = RREventType.Plugin,
    data = mapOf(
        "plugin" to plugin,
        "payload" to payload,
    ),
    timestamp = timestamp,
)

@PostHogInternal
public class RRDocumentNode(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf(
        "tag" to tag,
        "payload" to payload,
    ),
)

// TODO: create abstractions on top of RRWireframe, eg RRTextWireframe, etc
@PostHogInternal
public class RRWireframe(
    public val id: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val childWireframes: List<RRWireframe>? = null,
    public val type: String? = null, // image|input|radio group
    public val inputType: String? = null, // checkbox|radio|text|password|email|number|search|tel|url|select|textarea|button
    public val text: String? = null,
    public val label: String? = null,
    public val base64: String? = null,
    public val style: RRStyle? = null,
    public val disabled: Boolean? = null,
    public val checked: Boolean? = null,
    public val options: List<String>? = null,
    @Transient
    public val parentId: Int? = null,
)

@PostHogInternal
public class RRStyle(
    public var color: String? = null,
    public var backgroundColor: String? = null,
    public var borderWidth: Int? = null,
    public var borderRadius: Int? = null,
    public var borderColor: String? = null,
    public var fontSize: Int? = null,
    public var fontFamily: String? = null,
    public var horizontalAlign: String? = null,
    public var verticalAlign: String? = null,
    public var paddingTop: Int? = null,
    public var paddingBottom: Int? = null,
    public var paddingLeft: Int? = null,
    public var paddingRight: Int? = null,
)

public fun List<RREvent>.capture() {
    val properties = mutableMapOf<String, Any>(
        "\$snapshot_data" to this,
    )
    PostHog.capture("\$snapshot", properties = properties)
}
