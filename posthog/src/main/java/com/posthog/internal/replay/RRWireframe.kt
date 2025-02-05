package com.posthog.internal.replay

import com.posthog.PostHogInternal
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

// TODO: create abstractions on top of RRWireframe, eg RRTextWireframe, etc
@IgnoreJRERequirement
@PostHogInternal
public data class RRWireframe(
    public val id: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val childWireframes: List<RRWireframe>? = null,
    // text|image|rectangle|input|div|screenshot
    public val type: String? = null,
    // checkbox|radio|text|password|email|number|search|tel|url|select|textarea|button
    public val inputType: String? = null,
    public val text: String? = null,
    public val label: String? = null,
    // string or number
    public val value: Any? = null,
    public val base64: String? = null,
    public val style: RRStyle? = null,
    public val disabled: Boolean? = null,
    public val checked: Boolean? = null,
    public val options: List<String>? = null,
    @Transient
    public val parentId: Int? = null,
    public val max: Int? = null,
)
