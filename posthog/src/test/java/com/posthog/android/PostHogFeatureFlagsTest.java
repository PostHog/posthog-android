package com.posthog.android;

import static android.content.Context.MODE_PRIVATE;
import static com.posthog.android.TestUtils.SynchronousExecutor;
import static com.posthog.android.TestUtils.grantPermission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.Manifest;
import android.content.pm.PackageManager;

import com.posthog.android.payloads.CapturePayload;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PostHogFeatureFlagsTest {
    private PostHog posthog;
    @Mock ConnectionFactory connectionFactory;
    @Mock Integration integration;
    @Spy ExecutorService posthogExecutor = new SynchronousExecutor();

    @Before
    public void setUp() throws IOException, InterruptedException, PackageManager.NameNotFoundException {
        PostHog.INSTANCES.clear();

        initMocks(this);
        String host = "https://app.posthog.com";

        // Mock flags response
        Map decideResponse = new HashMap();
        Map flags = new HashMap();
        flags.put("enabled-flag", true);
        flags.put("disabled-flag", false);
        flags.put("multivariate-flag", "variant");
        decideResponse.put("featureFlags", flags);
        connectionFactory = TestUtils.mockDecideConnection(host, decideResponse);

        // Used by singleton tests.
        grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);

        posthog = new PostHog.Builder(RuntimeEnvironment.application, "foo", host)
                .connectionFactory(connectionFactory)
                .integration(integration)
                .executor(posthogExecutor)
                .build();
        // Wait for flags to reload on initializing PostHog
        Thread.sleep(1000);
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.application
                .getSharedPreferences("posthog-android-qaz", MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void getFeatureFlag_FlagDoesntExist_ReturnDefault() {
        assertThat(posthog.getFeatureFlag("nonexistent-flag", false))
                .isEqualTo(false);
    }

    @Test
    public void getFeatureFlag_EnabledFlag_ReturnCorrectFlagValue() {
        assertThat(posthog.getFeatureFlag("enabled-flag", false))
                .isEqualTo(true);
    }

    @Test
    public void getFeatureFlag_SendEventOption_CaptureCall() {
        Map options = new HashMap();
        options.put("send_event", true);
        posthog.getFeatureFlag("enabled-flag", false, options);

        verify(integration)
                .capture(
                        argThat(
                                new TestUtils.NoDescriptionMatcher<CapturePayload>() {
                                    @Override
                                    protected boolean matchesSafely(CapturePayload payload) {
                                        return payload.event().equals("$feature_flag_called")
                                                && payload.properties().get("$feature_flag").equals("enabled-flag")
                                                && payload.properties().get("$feature_flag_response").equals(true);
                                    }
                                }));

        // Multivariate
        posthog.getFeatureFlag("multivariate-flag", false, options);
        verify(integration)
                .capture(
                        argThat(
                                new TestUtils.NoDescriptionMatcher<CapturePayload>() {
                                    @Override
                                    protected boolean matchesSafely(CapturePayload payload) {
                                        return payload.event().equals("$feature_flag_called")
                                                && payload.properties().get("$feature_flag").equals("multivariate-flag")
                                                && payload.properties().get("$feature_flag_response").equals("variant");
                                    }
                                }));
    }

    @Test
    public void getFeatureFlag_DisabledFlag_ReturnCorrectFlagValue() {
        assertThat(posthog.isFeatureEnabled("disabled-flag", false))
                .isEqualTo(false);
    }


    @Test
    public void getFeatureFlag_Multivariate_ReturnCorrectFlagValue() {
        assertThat(posthog.getFeatureFlag("multivariate-flag", false))
                .isEqualTo("variant");
    }

    @Test
    public void isFeatureEnabled_FlagDoesntExist_ReturnDefault() {
        assertThat(posthog.isFeatureEnabled("nonexistent-flag", false))
                .isEqualTo(false);
    }

    @Test
    public void isFeatureEnabled_EnabledFlag_ReturnCorrectFlagValue() {
        assertThat(posthog.isFeatureEnabled("enabled-flag", false))
                .isEqualTo(true);
    }

    @Test
    public void isFeatureEnabled_DisabledFlag_ReturnCorrectFlagValue() {
        assertThat(posthog.isFeatureEnabled("disabled-flag", false))
                .isEqualTo(false);
    }

    @Test
    public void isFeatureEnabled_Multivariate_ReturnCorrectFlagValue() {
        assertThat(posthog.isFeatureEnabled("multivariate-flag", false))
                .isEqualTo(true);
    }
}
