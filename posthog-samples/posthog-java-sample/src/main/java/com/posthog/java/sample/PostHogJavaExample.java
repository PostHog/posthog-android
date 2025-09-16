package com.posthog.java.sample;

import com.posthog.CaptureEvent;
import com.posthog.CaptureOptions;
import com.posthog.PersonProfiles;
import com.posthog.PostHog;
import com.posthog.PostHogConfig;
import com.posthog.PostHogInterface;
import com.posthog.PostHogStateless;
import com.posthog.PostHogStatelessInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple Java 1.8 example demonstrating PostHog usage
 */
public class PostHogJavaExample {

    public static void main(String[] args) {
        // Configure PostHog
        PostHogConfig config = PostHogConfig
                .builder("phc_api_key")
                .host("http://localhost:8081")
                .build();

        PostHogInterface postHog = PostHog.with(config);

        clientSide(postHog);
        serverSide((PostHogStatelessInterface) postHog, "some-user-distinct-id");
    }

    private static void serverSide(PostHogStatelessInterface postHog, String distinctId) {
        postHog.groupStateless(distinctId, "company", "some-company-id", null);
        postHog.captureStateless(distinctId, "new-purchase", CaptureOptions
                .builder()
                .property("item", "SKU-0000")
                .property("sale", false)
                .build());
        postHog.identify(
                "distinct-id",
                new HashMap<String, String>() {{
                    put("email", "user@example.com");
                }},
                null
        );

        if (postHog.isFeatureEnabledStateless(distinctId, "beta-feature", false)) {
            System.out.println("The feature is enabled.");
        }

        Object flagValue = postHog.getFeatureFlagStateless(distinctId, "multi-variate-flag", "default");
        String flagVariate = flagValue instanceof String ? (String) flagValue : "default";
        Object flagPayload = postHog.getFeatureFlagPayloadStateless(distinctId, flagVariate, new HashMap<String, String>());
        System.out.println("Received flag payload: " + flagPayload);
    }

    private static void clientSide(PostHogInterface postHog) {
        postHog.group("company", "some-company-id", null);
        postHog.capture("add-to-cart", CaptureOptions.builder().property("item", "SKU-00000"));
        postHog.identify(
                "distinct-id",
                new HashMap<String, String>() {{
                    put("email", "user@example.com");
                }},
                null
        );

        if (postHog.isFeatureEnabled("beta-feature", false)) {
            System.out.println("The feature is enabled.");
        }

        Object flagValue = postHog.getFeatureFlag("multi-variate-flag", "default");
        String flagVariate = flagValue instanceof String ? (String) flagValue : "default";
        Object flagPayload = postHog.getFeatureFlagPayload(flagVariate, new HashMap<String, String>());
        System.out.println("Received flag payload: " + flagPayload);
    }
}