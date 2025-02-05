package com.posthog.internal.replay

public class RRMousePosition(
    public val x: Int,
    public val y: Int,
    public val id: Int,
    public val timeOffset: Long? = null,
)
