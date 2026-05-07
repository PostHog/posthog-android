package com.posthog.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PostHogAndroidConfigJavaTest {
    @Test
    public void apiKeyFromJavaIsTrimmed() {
        PostHogAndroidConfig config = new PostHogAndroidConfig(" \n" + UtilsKt.API_KEY + "\t ");

        assertEquals(UtilsKt.API_KEY, config.getApiKey());
    }
}
