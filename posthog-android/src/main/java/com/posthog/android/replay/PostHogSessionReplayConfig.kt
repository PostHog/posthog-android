package com.posthog.android.replay

public class PostHogSessionReplayConfig(
    public var maskAllTextInputs: Boolean = true,
    public var maskAllImages: Boolean = true,
    public var captureLogcat: Boolean = true,
)
