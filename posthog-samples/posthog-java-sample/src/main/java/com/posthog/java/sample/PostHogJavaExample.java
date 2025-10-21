package com.posthog.java.sample;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogFeatureFlagOptions;
import com.posthog.server.PostHogInterface;

import java.util.HashMap;

/**
 * Simple Java 1.8 example demonstrating PostHog usage
 */
public class PostHogJavaExample {

    public static void main(String[] args) {
        PostHogConfig config = PostHogConfig
                .builder("phc_wxtaSxv9yC8UYxUAxNojluoAf41L8p6SJZmiTMtS8jA")
                .personalApiKey("phs_DuaFTmUtxQNj5R2W03emB1jMLIX5XwDvrt3DKfi5uYNcxzd")
                .host("http://localhost:8010")
                .localEvaluation(true)
                .debug(true)
                .build();

        PostHogInterface posthog = PostHog.with(config);

        posthog.group("distinct-id", "company", "some-company-id");
        posthog.capture(
                "distinct-id",
                "new-purchase",
                PostHogCaptureOptions
                        .builder()
                        .property("item", "SKU-0000")
                        .property("sale", false)
                        .build());

        HashMap<String, Object> userProperties = new HashMap<>();
        userProperties.put("email", "user@example.com");
        posthog.identify("distinct-id", userProperties);

        // AVOID - Anonymous inner class holds reference to outer class.
        // The following won't serialize properly.
        // posthog.identify("user-123", new HashMap<String, Object>() {{
        // put("key", "value");
        // }});

        posthog.alias("distinct-id", "alias-id");

        // Feature flag examples with local evaluation
        if (posthog.isFeatureEnabled("distinct-id", "beta-feature", false)) {
            System.out.println("The feature is enabled.");
        }

        Object flagValue = posthog.getFeatureFlag("distinct-id", "multi-variate-flag", "default");
        String flagVariate = flagValue instanceof String ? (String) flagValue : "default";
        Object flagPayload = posthog.getFeatureFlagPayload("distinct-id", "multi-variate-flag");

        System.out.println("The flag variant was: " + flagVariate);
        System.out.println("Received flag payload: " + flagPayload);

        Boolean hasFilePreview = posthog.isFeatureEnabled(
                "distinct-id",
                "file-previews",
                PostHogFeatureFlagOptions
                        .builder()
                        .defaultValue(false)
                        .personProperty("email", "example@example.com")
                        .build());

        System.out.println("File previews enabled: " + hasFilePreview);

        posthog.flush();
        posthog.close();
    }
}