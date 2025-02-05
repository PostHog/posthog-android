package com.posthog.internal.replay

import com.posthog.PostHogInternal

@PostHogInternal
public data class RRStyle(
    public var color: String? = null,
    public var backgroundColor: String? = null,
    public var backgroundImage: String? = null,
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
    public var bar: String? = null,
    public var iconLeft: String? = null,
    public var iconRight: String? = null,
)
