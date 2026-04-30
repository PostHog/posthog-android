package com.posthog.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PostHogAndroidConfigJavaTest {
    @Test
    public void nullApiKeyFromJavaDefaultsToEmptyString() {
        PostHogAndroidConfig config = new PostHogAndroidConfig(null);

        assertEquals("", config.getApiKey());
    }
}
