/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment.io, Inc.
 *
 * Copyright (c) 2020 Hiberly Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.posthog.android;

import static android.content.Context.MODE_PRIVATE;
import static com.posthog.android.PostHog.LogLevel.NONE;
import static com.posthog.android.PostHog.LogLevel.VERBOSE;
import static com.posthog.android.TestUtils.SynchronousExecutor;
import static com.posthog.android.TestUtils.grantPermission;
import static com.posthog.android.TestUtils.mockApplication;
import static com.posthog.android.Utils.createContext;
import static com.posthog.android.internal.Utils.DEFAULT_FLUSH_INTERVAL;
import static com.posthog.android.internal.Utils.DEFAULT_FLUSH_QUEUE_SIZE;
import static com.posthog.android.internal.Utils.isNullOrEmpty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import com.posthog.android.TestUtils.NoDescriptionMatcher;
import com.posthog.android.payloads.AliasPayload;
import com.posthog.android.payloads.IdentifyPayload;
import com.posthog.android.payloads.ScreenPayload;
import com.posthog.android.payloads.CapturePayload;
import com.posthog.android.internal.Utils.PostHogNetworkExecutorService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.data.MapEntry;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PostHogTest {
  @Mock Properties.Cache propertiesCache;
  @Mock Persistence.Cache persistenceCache;
  @Mock Options defaultOptions;
  @Spy PostHogNetworkExecutorService networkExecutor;
  @Spy ExecutorService posthogExecutor = new SynchronousExecutor();
  @Mock Client client;
  @Mock Stats stats;
  @Mock Integration integration;
  PostHogFeatureFlags postHogFeatureFlags;
  private BooleanPreference optOut;
  private Application application;
  private Properties properties;
  private Persistence persistence;
  private PostHogContext posthogContext;
  private PostHog posthog;

  @Before
  public void setUp() throws IOException, NameNotFoundException {
    PostHog.INSTANCES.clear();

    initMocks(this);
    application = mockApplication();
    properties = Properties.create();
    when(propertiesCache.get()).thenReturn(properties);
    persistence = Persistence.create();
    persistence.putEnabledFeatureFlags(new ValueMap().putValue("enabled-flag", true).putValue("multivariate-flag", "blah"));
    when(persistenceCache.get()).thenReturn(persistence);

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 100;
    packageInfo.versionName = "1.0.0";

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    posthogContext = createContext().putValue("$lib", "posthog-android-custom-lib");

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("posthog-test-qaz", MODE_PRIVATE);
    optOut = new BooleanPreference(sharedPreferences, "opt-out-test", false);

    posthog =
            new PostHog(
                    application,
                    networkExecutor,
                    stats,
                    propertiesCache,
                    persistenceCache,
                    posthogContext,
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
                    optOut,
                    Crypto.none(),
                    Collections.<Middleware>emptyList(),
                    integration,
                    postHogFeatureFlags
            );

    // Used by singleton tests.
    grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);
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
  public void invalidIdentify() {
    try {
      posthog.identify(null, null, null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Either distinctId or some properties must be provided.");
    }
  }

  @Test
  public void identify() {
    posthog.identify("tim", new Properties().putValue("username", "f2tim"), null);

    verify(integration)
        .identify(
            argThat(
                new NoDescriptionMatcher<IdentifyPayload>() {
                  @Override
                  protected boolean matchesSafely(IdentifyPayload item) {
                    return item.distinctId().equals("tim")
                        && item.userProperties().get("username").equals("f2tim")
                        && item.properties().get("$lib").equals("posthog-android-custom-lib");
                  }
                }));
  }

  @Test
  public void identifyUpdatesCache() {
    posthog.identify("foo", new Properties().putValue("bar", "qaz"), null);

    assertThat(properties)
        .contains(MapEntry.entry("distinctId", "foo"))
        .contains(MapEntry.entry("bar", "qaz"));
    verify(propertiesCache).set(properties);
    verify(integration)
        .identify(
            argThat(
                new NoDescriptionMatcher<IdentifyPayload>() {
                  @Override
                  protected boolean matchesSafely(IdentifyPayload item) {
                    // Exercises a bug where payloads didn't pick up distinctId in identify correctly.
                    // https://github.com/posthog/posthog-android/issues/169
                    return item.distinctId().equals("foo");
                  }
                }));
  }

  @Test
  public void invalidCapture() {
    try {
      posthog.capture(null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
    try {
      posthog.capture("   ");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
  }

  @Test
  public void capture() {
    posthog.capture("wrote tests", new Properties().putValue("url", "github.com"));
    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("wrote tests")
                        && //
                        payload.properties().get("url").equals("github.com")
                        &&
                        payload.properties().get("$lib").equals("posthog-android-custom-lib");
                  }
                }));
  }

  @Test
  public void captureWithSendFeatureFlags() {
    posthog.capture("capture with flags", new Properties().putValue("url", "github.com"), new Options().putContext("send_feature_flags", true));
    verify(integration)
            .capture(
                    argThat(
                            new NoDescriptionMatcher<CapturePayload>() {
                              @Override
                              protected boolean matchesSafely(CapturePayload payload) {
                                return payload.event().equals("capture with flags")
                                        && //
                                        payload.properties().get("url").equals("github.com")
                                        &&
                                        payload.properties().get("$lib").equals("posthog-android-custom-lib")
                                        &&
                                        payload.properties().get("$feature/enabled-flag").equals(true)
                                        &&
                                        payload.properties().get("$feature/multivariate-flag").equals("blah")
                                        &&
                                        payload.properties().get("$active_feature_flags").equals(Arrays.asList("enabled-flag", "multivariate-flag"));
                              }
                            }));
  }

  @Test
  public void invalidScreen() throws Exception {
    try {
      posthog.screen(null);
      fail("null category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("name must be provided.");
    }

    try {
      posthog.screen("");
      fail("empty category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("name must be provided.");
    }
  }

  @Test
  public void screen() {
    posthog.screen("saw tests", new Properties().putValue("url", "github.com"));
    verify(integration)
        .screen(
            argThat(
                new NoDescriptionMatcher<ScreenPayload>() {
                  @Override
                  protected boolean matchesSafely(ScreenPayload payload) {
                    return payload.name().equals("saw tests")
                        && //
                        payload.properties().get("url").equals("github.com")
                        &&
                        payload.properties().get("$lib").equals("posthog-android-custom-lib");
                  }
                }));
  }

  @Test
  public void optionsCustomContext() {
    posthog.capture("foo", null, new Options().putContext("from_tests", true));

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.properties().get("from_tests") == Boolean.TRUE;
                  }
                }));
  }

  @Test
  public void optOutDisablesEvents() throws IOException {
    posthog.optOut(true);
    posthog.capture("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void invalidAlias() {
    try {
      posthog.alias(null);
      fail("null new id should throw error");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("newId must not be null or empty.");
    }
  }

  @Test
  public void alias() {
    final String anonymousId = properties.anonymousId();
    posthog.alias("foo");

    verify(integration)
            .alias(
                    argThat(
                            new NoDescriptionMatcher<AliasPayload>() {
                              @Override
                              protected boolean matchesSafely(AliasPayload payload) {
                                return payload.event().equals("$create_alias")
                                        && //
                                        payload.properties().get("alias").equals("foo")
                                        && //
                                        payload.properties().get("distinct_id").equals(anonymousId)
                                        &&
                                        payload.properties().get("$lib").equals("posthog-android-custom-lib");
                              }
                            }));

  }

  @Test
  public void flush() {
    posthog.flush();

    verify(integration).flush();
  }

  @Test
  public void reset() {
    posthog.reset();

    verify(integration).reset();
  }

  @Test
  public void getSnapshot() throws Exception {
    posthog.getSnapshot();

    verify(stats).createSnapshot();
  }

  @Test
  public void resetClearsPropertiesAndUpdatesContext() {
    posthog.reset();

    verify(propertiesCache).delete();
    verify(propertiesCache)
        .set(
            argThat(
                new TypeSafeMatcher<Properties>() {
                  @Override
                  protected boolean matchesSafely(Properties properties) {
                    return !isNullOrEmpty(properties.anonymousId());
                  }

                  @Override
                  public void describeTo(Description description) {}
                }));
    assertThat(propertiesCache.get()).hasSize(1).containsKey("anonymousId");
  }

  @Test
  public void shutdown() {
    assertThat(posthog.shutdown).isFalse();
    posthog.shutdown();
    verify(application).unregisterActivityLifecycleCallbacks(posthog.activityLifecycleCallback);
    verify(stats).shutdown();
    verify(networkExecutor).shutdown();
    assertThat(posthog.shutdown).isTrue();
    try {
      posthog.capture("foo");
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }

    try {
      posthog.flush();
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }
  }

  @Test
  public void shutdownTwice() {
    assertThat(posthog.shutdown).isFalse();
    posthog.shutdown();
    posthog.shutdown();
    verify(stats).shutdown();
    assertThat(posthog.shutdown).isTrue();
  }

  @Test
  public void shutdownDisallowedOnCustomSingletonInstance() throws Exception {
    PostHog.singleton = null;
    try {
      PostHog posthog = new PostHog.Builder(RuntimeEnvironment.application, "foo").build();
      PostHog.setSingletonInstance(posthog);
      posthog.shutdown();
      fail("Calling shutdown() on static singleton instance should throw");
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @Test
  public void setSingletonInstanceMayOnlyBeCalledOnce() {
    PostHog.singleton = null;

    PostHog posthog = new PostHog.Builder(RuntimeEnvironment.application, "foo").build();
    PostHog.setSingletonInstance(posthog);

    try {
      PostHog.setSingletonInstance(posthog);
      fail("Can't set singleton instance twice.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test
  public void setSingletonInstanceAfterWithFails() {
    PostHog.singleton = null;

    PostHog.setSingletonInstance(
        new PostHog.Builder(RuntimeEnvironment.application, "foo") //
            .build());

    PostHog posthog = new PostHog.Builder(RuntimeEnvironment.application, "bar").build();
    try {
      PostHog.setSingletonInstance(posthog);
      fail("Can't set singleton instance after with().");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test
  public void setSingleInstanceReturnedFromWith() {
    PostHog.singleton = null;
    PostHog posthog = new PostHog.Builder(RuntimeEnvironment.application, "foo").build();
    PostHog.setSingletonInstance(posthog);
    assertThat(PostHog.with(RuntimeEnvironment.application)).isSameAs(posthog);
  }

  @Test
  public void multipleInstancesWithSameTagThrows() throws Exception {
    new PostHog.Builder(RuntimeEnvironment.application, "foo").build();
    try {
      new PostHog.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
      fail("Creating client with duplicate should throw.");
    } catch (IllegalStateException expected) {
      assertThat(expected) //
          .hasMessageContaining("Duplicate posthog client created with tag: foo.");
    }
  }

  @Test
  public void multipleInstancesWithSameTagIsAllowedAfterShutdown() throws Exception {
    new PostHog.Builder(RuntimeEnvironment.application, "foo").build().shutdown();
    new PostHog.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
  }

  @Test
  public void getSnapshotInvokesStats() throws Exception {
    posthog.getSnapshot();
    verify(stats).createSnapshot();
  }

  @Test
  public void invalidURlsThrowAndNotCrash() throws Exception {
    ConnectionFactory connection = new ConnectionFactory();

    try {
      connection.openConnection("SOME_BUSTED_URL");
      fail("openConnection did not throw when supplied an invalid URL as expected.");
    } catch (IOException expected) {
      assertThat(expected).hasMessageContaining("Attempted to use malformed url");
      assertThat(expected).isInstanceOf(IOException.class);
    }
  }

  @Test
  public void captureApplicationLifecycleEventsInstalled() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    callback.get().onActivityCreated(null, null);

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Installed")
                        && //
                        payload.properties().getString("version").equals("1.0.0")
                        && //
                        payload.properties().getInt("build", -1) == 100;
                  }
                }));

    callback.get().onActivityCreated(null, null);
    verify(integration, times(2)).onActivityCreated(null, null);
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void captureApplicationLifecycleEventsUpdated() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 101;
    packageInfo.versionName = "1.0.1";

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("posthog-android-qaz", MODE_PRIVATE);
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putInt("build", 100);
    editor.putString("version", "1.0.0");
    editor.apply();
    when(application.getSharedPreferences("posthog-android-qaz", MODE_PRIVATE))
        .thenReturn(sharedPreferences);

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    callback.get().onActivityCreated(null, null);

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Updated")
                        && //
                        payload.properties().getString("previous_version").equals("1.0.0")
                        && //
                        payload.properties().getInt("previous_build", -1) == 100
                        && //
                        payload.properties().getString("version").equals("1.0.1")
                        && //
                        payload.properties().getInt("build", -1) == 101;
                  }
                }));
  }

  @Test
  public void recordScreenViews() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
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
            true,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    Activity activity = mock(Activity.class);
    PackageManager packageManager = mock(PackageManager.class);
    ActivityInfo info = mock(ActivityInfo.class);

    when(activity.getPackageManager()).thenReturn(packageManager);
    //noinspection WrongConstant
    when(packageManager.getActivityInfo(any(ComponentName.class), eq(PackageManager.GET_META_DATA)))
        .thenReturn(info);
    when(info.loadLabel(packageManager)).thenReturn("Foo");

    callback.get().onActivityStarted(activity);

    verify(integration)
        .screen(
            argThat(
                new NoDescriptionMatcher<ScreenPayload>() {
                  @Override
                  protected boolean matchesSafely(ScreenPayload payload) {
                    return payload.name().equals("Foo");
                  }
                }));
  }

  @Test
  public void captureDeepLinks() {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            true,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    final String expectedUrl = "app://capture.com/open?utm_id=12345&gclid=abcd&nope=";

    Activity activity = mock(Activity.class);
    Intent intent = mock(Intent.class);
    Uri uri = Uri.parse(expectedUrl);

    when(intent.getData()).thenReturn(uri);
    when(activity.getIntent()).thenReturn(intent);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Deep Link Opened")
                        && payload.properties().getString("url").equals(expectedUrl)
                        && payload.properties().getString("gclid").equals("abcd")
                        && payload.properties().getString("utm_id").equals("12345");
                  }
                }));
  }

  @Test
  public void captureDeepLinks_disabled() {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    final String expectedUrl = "app://capture.com/open?utm_id=12345&gclid=abcd&nope=";

    Activity activity = mock(Activity.class);
    Intent intent = mock(Intent.class);
    Uri uri = Uri.parse(expectedUrl);

    when(intent.getData()).thenReturn(uri);
    when(activity.getIntent()).thenReturn(intent);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Deep Link Opened")
                        && payload.properties().getString("url").equals(expectedUrl)
                        && payload.properties().getString("gclid").equals("abcd")
                        && payload.properties().getString("utm_id").equals("12345");
                  }
                }));
  }

  @Test
  public void captureDeepLinks_null() {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    Activity activity = mock(Activity.class);

    when(activity.getIntent()).thenReturn(null);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Deep Link Opened");
                  }
                }));
  }

  @Test
  public void trackDeepLinks_nullData() {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
            new AtomicReference<>();
    doNothing()
            .when(application)
            .registerActivityLifecycleCallbacks(
                    argThat(
                            new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                              @Override
                              protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                                callback.set(item);
                                return true;
                              }
                            }));

    posthog =
          new PostHog(
                  application,
                  networkExecutor,
                  stats,
                  propertiesCache,
                  persistenceCache,
                  posthogContext,
                  defaultOptions,
                  Logger.with(NONE),
                  "qaz",
                  client,
                  Cartographer.INSTANCE,
                  "foo",
                  "https://app.posthog.com",
                  DEFAULT_FLUSH_QUEUE_SIZE,
                  DEFAULT_FLUSH_INTERVAL,
                  posthogExecutor,
                  true,
                  new CountDownLatch(0),
                  false,
                  false,
                  optOut,
                  Crypto.none(),
                  Collections.<Middleware>emptyList(),
                  integration,
                  postHogFeatureFlags
          );

    Activity activity = mock(Activity.class);

    Intent intent = mock(Intent.class);

    when(activity.getIntent()).thenReturn(intent);
    when(intent.getData()).thenReturn(null);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
            .capture(
                    argThat(
                            new NoDescriptionMatcher<CapturePayload>() {
                              @Override
                              protected boolean matchesSafely(CapturePayload payload) {
                                return payload.event().equals("Deep Link Opened");
                              }
                            }));
  }

  @Test
  public void registerActivityLifecycleCallbacks() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
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
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    Activity activity = mock(Activity.class);
    Bundle bundle = new Bundle();

    callback.get().onActivityCreated(activity, bundle);
    verify(integration).onActivityCreated(activity, bundle);

    callback.get().onActivityStarted(activity);
    verify(integration).onActivityStarted(activity);

    callback.get().onActivityResumed(activity);
    verify(integration).onActivityResumed(activity);

    callback.get().onActivityPaused(activity);
    verify(integration).onActivityPaused(activity);

    callback.get().onActivityStopped(activity);
    verify(integration).onActivityStopped(activity);

    callback.get().onActivitySaveInstanceState(activity, bundle);
    verify(integration).onActivitySaveInstanceState(activity, bundle);

    callback.get().onActivityDestroyed(activity);
    verify(integration).onActivityDestroyed(activity);

    verifyNoMoreInteractions(integration);
  }

  @Test
  public void captureApplicationLifecycleEventsApplicationOpened() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    callback.get().onActivityCreated(null, null);
    callback.get().onActivityResumed(null);

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Opened")
                        && payload.properties().getString("version").equals("1.0.0")
                        && payload.properties().getInt("build", -1) == 100
                        && !payload.properties().getBoolean("from_background", true);
                  }
                }));
  }

  @Test
  public void captureApplicationLifecycleEventsApplicationBackgrounded()
      throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    Activity backgroundedActivity = mock(Activity.class);
    when(backgroundedActivity.isChangingConfigurations()).thenReturn(false);

    callback.get().onActivityCreated(null, null);
    callback.get().onActivityResumed(null);
    callback.get().onActivityStopped(backgroundedActivity);

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Backgrounded");
                  }
                }));
  }

  @Test
  public void captureApplicationLifecycleEventsApplicationForegrounded()
      throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
            "qaz",
            client,
            Cartographer.INSTANCE,
            "foo",
            "https://app.posthog.com",
            DEFAULT_FLUSH_QUEUE_SIZE,
            DEFAULT_FLUSH_INTERVAL,
            posthogExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    Activity backgroundedActivity = mock(Activity.class);
    when(backgroundedActivity.isChangingConfigurations()).thenReturn(false);

    callback.get().onActivityCreated(null, null);
    callback.get().onActivityResumed(null);
    callback.get().onActivityStopped(backgroundedActivity);
    callback.get().onActivityResumed(null);

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Backgrounded");
                  }
                }));

    verify(integration)
        .capture(
            argThat(
                new NoDescriptionMatcher<CapturePayload>() {
                  @Override
                  protected boolean matchesSafely(CapturePayload payload) {
                    return payload.event().equals("Application Opened")
                        && payload.properties().getBoolean("from_background", false);
                  }
                }));
  }

  @Test
  public void unregisterActivityLifecycleCallbacks() throws NameNotFoundException {
    PostHog.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> registeredCallback =
        new AtomicReference<>();
    final AtomicReference<Application.ActivityLifecycleCallbacks> unregisteredCallback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    registeredCallback.set(item);
                    return true;
                  }
                }));
    doNothing()
        .when(application)
        .unregisterActivityLifecycleCallbacks(
            argThat(
                new NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    unregisteredCallback.set(item);
                    return true;
                  }
                }));

    posthog =
        new PostHog(
            application,
            networkExecutor,
            stats,
            propertiesCache,
            persistenceCache,
            posthogContext,
            defaultOptions,
            Logger.with(NONE),
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
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            integration, postHogFeatureFlags);

    assertThat(posthog.shutdown).isFalse();
    posthog.shutdown();

    // Same callback was registered and unregistered
    assertThat(posthog.activityLifecycleCallback).isSameAs(registeredCallback.get());
    assertThat(posthog.activityLifecycleCallback).isSameAs(unregisteredCallback.get());

    Activity activity = mock(Activity.class);
    Bundle bundle = new Bundle();

    // Verify callbacks do not call through after shutdown
    registeredCallback.get().onActivityCreated(activity, bundle);
    verify(integration, never()).onActivityCreated(activity, bundle);

    registeredCallback.get().onActivityStarted(activity);
    verify(integration, never()).onActivityStarted(activity);

    registeredCallback.get().onActivityResumed(activity);
    verify(integration, never()).onActivityResumed(activity);

    registeredCallback.get().onActivityPaused(activity);
    verify(integration, never()).onActivityPaused(activity);

    registeredCallback.get().onActivityStopped(activity);
    verify(integration, never()).onActivityStopped(activity);

    registeredCallback.get().onActivitySaveInstanceState(activity, bundle);
    verify(integration, never()).onActivitySaveInstanceState(activity, bundle);

    registeredCallback.get().onActivityDestroyed(activity);
    verify(integration, never()).onActivityDestroyed(activity);

    verifyNoMoreInteractions(integration);
  }
}
