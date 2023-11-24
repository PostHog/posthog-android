package com.posthog.android.replay.internal

internal open class RREvent(
    val type: RREventType,
    open val data: Any? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

//internal enum class RRNodeType(val value: Int) {
//    Document(0),
//    DocumentType(1),
//    Element(2),
//    Text(3),
//    CDATA(4),
//    Comment(5),
//}

internal enum class RREventType(val value: Int) {
    DomContentLoaded(0),
    Load(1),
    FullSnapshot(2),
    IncrementalSnapshot(3),
    Meta(4),
    Custom(5),
    Plugin(6),
}

//internal enum class RRIncrementalSource(val value: Int) {
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
//}

internal class RRDomContentLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.DomContentLoaded,
    timestamp = timestamp,
)

internal class RRLoadedEvent(timestamp: Long) : RREvent(
    type = RREventType.Load,
    timestamp = timestamp,
)

internal class RRFullSnapshotEvent(node: Any, initialOffsetTop: Int, initialOffsetLeft: Int) : RREvent(
    type = RREventType.FullSnapshot,
    data = mapOf("node" to node,
        "initialOffsetTop" to initialOffsetTop,
        "initialOffsetLeft" to initialOffsetLeft),
)

internal class RRIncrementalSnapshotEvent(override val data: Any? = null) : RREvent(
    type = RREventType.IncrementalSnapshot,
    data = data,
)

internal class RRMetaEvent(href: String, width: Int, height: Int, timestamp: Long) : RREvent(
    type = RREventType.Meta,
    data = mapOf("href" to href,
        "width" to width,
        "height" to height),
    timestamp = timestamp,
)

internal class RRCustomEvent(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf("tag" to tag,
        "payload" to payload),
)

internal class RRPluginEvent(plugin: String, payload: Any) : RREvent(
    type = RREventType.Plugin,
    data = mapOf("plugin" to plugin,
        "payload" to payload),
)

internal class RRDocumentNode(tag: String, payload: Any) : RREvent(
    type = RREventType.Custom,
    data = mapOf("tag" to tag,
        "payload" to payload),
)

// type
// name
// publicId
// systemId