package com.posthog.internal.replay

import com.posthog.PostHogInternal

// TODO: create abstractions on top of RRWireframe, eg RRTextWireframe, etc
@PostHogInternal
public class RRWireframe(
    public val id: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val childWireframes: List<RRWireframe>? = null,
    public val type: String? = null, // text|image|rectangle|input|div
    public val inputType: String? = null, // checkbox|radio|text|password|email|number|search|tel|url|select|textarea|button
    public val text: String? = null,
    public val label: String? = null,
    public val value: Any? = null, // string or number
    public val base64: String? = null,
    public val style: RRStyle? = null,
    public val disabled: Boolean? = null,
    public val checked: Boolean? = null,
    public val options: List<String>? = null,
    @Transient
    public val parentId: Int? = null,
    public val max: Int? = null,
)
