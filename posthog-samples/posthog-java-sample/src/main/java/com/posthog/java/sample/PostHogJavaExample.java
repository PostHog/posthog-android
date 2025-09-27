package com.posthog.java.sample;

import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHog;
import com.posthog.server.PostHogInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple Java 1.8 example demonstrating PostHog usage
 */
public class PostHogJavaExample {
    public static void main(String[] args) {
        PostHogConfig config = PostHogConfig
                .builder("phc_wz4KZkikEluCCdfY2B2h7MXYygNGdTqFgjbU7I1ZdVR")
                .build();

        PostHogInterface postHog = PostHog.with(config);

        postHog.group("distinct-id", "company", "some-company-id");
        postHog.capture(
                "distinct-id",
                "new-purchase",
                PostHogCaptureOptions
                        .builder()
                        .property("item", "SKU-0000")
                        .property("sale", false)
                        .build());

        HashMap<String, Object> userProperties = new HashMap<>();
        userProperties.put("email", "user@example.com");
        postHog.identify("distinct-id", userProperties);

        // AVOID - Anonymous inner class holds reference to outer class.
        // The following won't serialize properly.
        // postHog.identify("user-123", new HashMap<String, Object>() {{
        //      put("key", "value");
        // }});

        postHog.alias("distinct-id", "alias-id");


        if (postHog.isFeatureEnabled("distinct-id", "beta-feature", false)) {
            System.out.println("The feature is enabled.");
        }

        Object flagValue = postHog.getFeatureFlag("distinct-id", "multi-variate-flag", "default");
        String flagVariate = flagValue instanceof String ? (String) flagValue : "default";
        Object flagPayload = postHog.getFeatureFlagPayload("distinct-id", "multi-variate-flag");

        System.out.println("The flag variant was: " + flagVariate);
        System.out.println("Received flag payload: " + flagPayload);

        postHog.flush();
        postHog.close();
    }
}