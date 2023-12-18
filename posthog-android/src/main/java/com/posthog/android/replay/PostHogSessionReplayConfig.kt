package com.posthog.android.replay

public class PostHogSessionReplayConfig(
    /**
     * Enable masking of all text input fields
     * Defaults to true
     */
    public var maskAllTextInputs: Boolean = true,

    /**
     * Enable masking of all images to a placeholder
     * Defaults to true
     */
    public var maskAllImages: Boolean = true,

    /**
     * Enable capturing of logcat as console events
     * Defaults to true
     */
    public var captureLogcat: Boolean = true,
)
