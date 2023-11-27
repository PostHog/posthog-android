package com.posthog.internal

public open class RREvent(
    public val type: RREventType,
    public open val data: Any? = null,
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

// internal enum class RRIncrementalSource(val value: Int) {
//    Mutation(0),
//    MouseMove(1),
//    MouseInteraction(2),
//    Scroll(3),
//    ViewportResize(4),
//    Input(5),
//    TouchMove(6),
//    MediaInteraction(7),
//    StyleSheetRule(8),
//    CanvasMutation(9),
//    Font(10),
//    Log(11),
//    Drag(12),
//    StyleDeclaration(13),
//    Selection(14),
//    AdoptedStyleSheet(15),
//    CustomElement(16),
// }

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

public class RRIncrementalSnapshotEvent(override val data: Any? = null) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = data,
)

public class RRMetaEvent(href: String, width: Int, height: Int, timestamp: Long) : RREvent(
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
)

public data class RRStyle(
    var color: String? = null,
    var backgroundColor: String? = null,
    var borderWidth: Int? = null,
    var borderRadius: Int? = null,
    var borderColor: String? = null,
)

// type
// name
// publicId
// systemId
