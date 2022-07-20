package com.posthog.android;

import static android.content.Context.MODE_PRIVATE;
import static com.posthog.android.PostHog.LogLevel.VERBOSE;
import static com.posthog.android.TestUtils.mockApplication;
import static com.posthog.android.Utils.createContext;
import static com.posthog.android.internal.Utils.DEFAULT_FLUSH_INTERVAL;
import static com.posthog.android.internal.Utils.DEFAULT_FLUSH_QUEUE_SIZE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.posthog.android.internal.Utils.PostHogNetworkExecutorService;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PostHogFeatureFlagsTest {
    private PostHog posthog;
    private Map<String, Boolean> flagCallReported;
    private Boolean reloadFeatureFlagsQueued;
    private Boolean reloadFeatureFlagsInAction;
    private Boolean featureFlagsLoaded;
    @Mock Client client;

    @Mock Properties.Cache propertiesCache;
    @Mock Persistence.Cache persistenceCache;
    @Mock Options defaultOptions;
    @Spy PostHogNetworkExecutorService networkExecutor;
    @Spy ExecutorService posthogExecutor = new TestUtils.SynchronousExecutor();
    @Mock Stats stats;
    @Mock Integration integration;
    PostHogFeatureFlags postHogFeatureFlags;
    private Application application;
    private Properties properties;
    private Persistence persistence;

    @Before
    public void setUp() throws IOException, PackageManager.NameNotFoundException {
        PostHog.INSTANCES.clear();

        initMocks(this);

        application = mockApplication();
        properties = Properties.create();
        when(propertiesCache.get()).thenReturn(properties);
        persistence = Persistence.create();
        when(persistenceCache.get()).thenReturn(persistence);

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.versionCode = 100;
        packageInfo.versionName = "1.0.0";

        PackageManager packageManager = mock(PackageManager.class);
        when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
        when(application.getPackageName()).thenReturn("com.foo");
        when(application.getPackageManager()).thenReturn(packageManager);

        SharedPreferences sharedPreferences =
                RuntimeEnvironment.application.getSharedPreferences("posthog-test-qaz", MODE_PRIVATE);

        posthog =
                new PostHog(
                        application,
                        networkExecutor,
                        stats,
                        propertiesCache,
                        persistenceCache,
                        createContext().putValue("$lib", "posthog-android-custom-lib"),
                        defaultOptions,
                        Logger.with(VERBOSE),
                        "qaz",
                        client,
                        Cartographer.INSTANCE,
                        "foo",
                        "https://app.posthog.com",
                        DEFAULT_FLUSH_QUEUE_SIZE,
                        DEFAULT_FLUSH_INTERVAL,
                        posthogExecutor,
                        false,
                        new CountDownLatch(0),
                        false,
                        false,
                        new BooleanPreference(sharedPreferences, "opt-out-test", false),
                        Crypto.none(),
                        Collections.<Middleware>emptyList(),
                        integration,
                        postHogFeatureFlags
                );
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
    public void getFeatureFlag_BeforeFlagsLoaded_IllegalStateException() {

    }

    @Test
    public void getFeatureFlag_FlagDoesntExist_ReturnDefault() {

    }

    @Test
    public void getFeatureFlag_HappyCase_ReturnCorrectFlagValue() {

    }

    @Test
    public void isFeatureEnabled_BeforeFlagsLoaded_IllegalStateException() {

    }

    @Test
    public void reloadFeatureFlags() {

    }
}
