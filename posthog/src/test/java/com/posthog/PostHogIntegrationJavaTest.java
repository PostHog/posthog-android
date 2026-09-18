package com.posthog;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PostHogIntegrationJavaTest {
    private static class ExistingIntegration implements PostHogIntegration {
        @Override
        public void install(PostHogInterface postHog) {}

        @Override
        public void uninstall() {}

        @Override
        public void onRemoteConfig(boolean loaded) {}
    }

    @Test
    public void existingJavaIntegrationInheritsOnChange() throws Exception {
        PostHogIntegration integration = new ExistingIntegration();
        integration.onChange();
        assertTrue(PostHogIntegration.class.getMethod("onChange").isDefault());
    }

    @Test
    public void legacyKotlinDefaultImplsBridgesRemainCallable() {
        PostHogIntegration integration = new ExistingIntegration();
        PostHogIntegration.DefaultImpls.uninstall(integration);
        PostHogIntegration.DefaultImpls.onRemoteConfig(integration, true);
    }
}
