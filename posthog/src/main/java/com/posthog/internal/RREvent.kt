package com.posthog.internal

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

public class RRDomContentLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.DomContentLoaded,
    timestamp = timestamp,
)

public class RRLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.Load,
    timestamp = timestamp,
)

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

public class RRIncrementalSnapshotEvent(
    mutationData: RRIncrementalMutationData? = null,
    timestamp: Long,
) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = mutationData,
    timestamp = timestamp,
)

public class RRIncrementalMouseInteractionEvent(
    mouseInteractionData: RRIncrementalMouseInteractionData? = null,
    timestamp: Long,
) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = mouseInteractionData,
    timestamp = timestamp,
)

public data class RRAddedNode(
    val wireframe: RRWireframe,
    val parentId: Int? = null,
)

public data class RRRemovedNode(
    val id: Int,
    val parentId: Int? = null,
)

public class RRIncrementalMutationData(
    public val adds: List<RRAddedNode>? = null,
    public val removes: List<RRRemovedNode>? = null,
    public val source: RRIncrementalSource = RRIncrementalSource.Mutation,
    // TODO: do we need updates?
)

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

public class RRIncrementalMouseInteractionData(
    public val id: Int,
    public val type: RRMouseInteraction,
    public val x: Int,
    public val y: Int,
    public val source: RRIncrementalSource = RRIncrementalSource.MouseInteraction,
    public val pointerType: Int = 2, // always Touch
)

public class RRMetaEvent(width: Int, height: Int, timestamp: Long, href: String) : RREvent(
    type = RREventType.Meta,
    data = mapOf(
        "href" to href,
        "width" to width,
        "height" to height,
    ),
    timestamp = timestamp,
)

public class RRCustomEvent(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf(
        "tag" to tag,
        "payload" to payload,
    ),
)

public class RRPluginEvent(plugin: String, payload: Any) : RREvent(
    type = RREventType.Plugin,
    data = mapOf(
        "plugin" to plugin,
        "payload" to payload,
    ),
)

public class RRDocumentNode(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf(
        "tag" to tag,
        "payload" to payload,
    ),
)

// TODO: create abstractions on top of RRWireframe, eg RRTextWireframe, etc
public data class RRWireframe(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val childWireframes: List<RRWireframe>? = null,
    val type: String? = null,
    val text: String? = null,
    val base64: String? = null,
    val style: RRStyle? = null,
    @Transient
    val parentId: Int? = null,
)

public data class RRStyle(
    var color: String? = null,
    var backgroundColor: String? = null,
    var borderWidth: Int? = null,
    var borderRadius: Int? = null,
    var borderColor: String? = null,
)
