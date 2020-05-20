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

import static android.Manifest.permission.INTERNET;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static com.posthog.android.PostHog.Builder;
import static com.posthog.android.PostHog.API_KEY_RESOURCE_IDENTIFIER;
import static com.posthog.android.TestUtils.grantPermission;
import static com.posthog.android.TestUtils.mockApplication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import com.posthog.core.BuildConfig;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, sdk = 18, manifest = Config.NONE)
public class PostHogBuilderTest {

  private Application context;

  @Before
  public void setUp() {
    initMocks(this);
    context = mockApplication();
    PostHog.INSTANCES.clear();
    when(context.getApplicationContext()).thenReturn(context);
  }

  @Test
  public void invalidContextThrowsException() throws Exception {
    try {
      new Builder(null, null);
      fail("Null context should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("Context must not be null.");
    }

    when(context.checkCallingOrSelfPermission(INTERNET)).thenReturn(PERMISSION_DENIED);
    try {
      new Builder(context, "foo");
      fail("Missing internet permission should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("INTERNET permission is required.");
    }
  }

  @Test
  public void invalidExecutorThrowsException() throws Exception {
    try {
      new Builder(context, "foo").networkExecutor(null);
      fail("Null executor should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("Executor service must not be null.");
    }
  }

  @Test
  public void invalidMiddlewareThrowsException() throws Exception {
    try {
      new Builder(context, "foo").middleware(null);
      fail("Null middleware should throw exception.");
    } catch (NullPointerException expected) {
      assertThat(expected).hasMessage("middleware == null");
    }

    try {
      Middleware middleware =
          new Middleware() {
            @Override
            public void intercept(Chain chain) {
              throw new AssertionError("should not be invoked");
            }
          };
      new Builder(context, "foo").middleware(middleware).middleware(middleware);
      fail("Registering middleware twice throw exception.");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessage("Middleware is already registered.");
    }
  }

  @Test
  public void invalidApiKeyThrowsException() throws Exception {
    try {
      new Builder(context, null);
      fail("Null apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }

    try {
      new Builder(context, "");
      fail("Empty apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }

    try {
      new Builder(context, "    ");
      fail("Blank apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }
  }

  @Test
  public void invalidApiKeyFromResourcesThrowsException() throws Exception {
    mockApiKeyInResources(context, null);

    try {
      PostHog.with(context);
      fail("Null apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }

    mockApiKeyInResources(context, "");
    try {
      PostHog.with(context);
      fail("Empty apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }

    mockApiKeyInResources(context, "   ");
    try {
      PostHog.with(context);
      fail("blank apiKey should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("apiKey must not be null or empty.");
    }
  }

  @Test
  public void invalidQueueSizeThrowsException() throws Exception {
    try {
      new Builder(context, "foo").flushQueueSize(-1);
      fail("flushQueueSize < 0 should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("flushQueueSize must be greater than or equal to zero.");
    }

    try {
      new Builder(context, "foo").flushQueueSize(0);
      fail("flushQueueSize = 0 should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("flushQueueSize must be greater than or equal to zero.");
    }

    try {
      new Builder(context, "foo").flushQueueSize(251);
      fail("flushQueueSize = 251 should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("flushQueueSize must be less than or equal to 250.");
    }
  }

  @Test
  public void invalidFlushIntervalThrowsException() throws Exception {
    try {
      new Builder(context, "foo").flushInterval(-1, TimeUnit.DAYS);
      fail("flushInterval < 0 should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("flushInterval must be greater than zero.");
    }

    try {
      new Builder(context, "foo").flushInterval(1, null);
      fail("null unit should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("timeUnit must not be null.");
    }
  }

  @Test
  public void invalidOptionsThrowsException() throws Exception {
    try {
      new Builder(context, "foo").defaultOptions(null);
      fail("null options should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("defaultOptions must not be null.");
    }
  }

  @Test
  public void invalidTagThrowsException() throws Exception {
    try {
      new Builder(context, "foo").tag(null);
      fail("Null tag should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("tag must not be null or empty.");
    }

    try {
      new Builder(context, "foo").tag("");
      fail("Empty tag should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("tag must not be null or empty.");
    }

    try {
      new Builder(context, "foo").tag("    ");
      fail("Blank tag should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("tag must not be null or empty.");
    }
  }

  @Test
  public void invalidLogLevelThrowsException() throws Exception {
    try {
      new Builder(context, "foo").logLevel(null);
      fail("Setting null LogLevel should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("LogLevel must not be null.");
    }
  }

  @Test
  public void invalidDefaultOptionsThrowsException() throws Exception {
    try {
      new Builder(context, "foo").defaultOptions(null);
      fail("Setting null defaultOptions should throw exception.");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("defaultOptions must not be null.");
    }
  }

  @Test
  public void defaultOptionsCopiesContext() throws Exception {
    grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);
    PostHog posthog = new Builder(RuntimeEnvironment.application, "foo").defaultOptions(new Options().putContext("$lib", "custom-library")).build();
    assertThat(posthog.defaultOptions.context().get("$lib")).isEqualTo("custom-library");
  }

  private void mockApiKeyInResources(Context context, String apiKey) {
    Resources resources = mock(Resources.class);
    when(context.getResources()).thenReturn(resources);

    when(resources.getIdentifier(eq(API_KEY_RESOURCE_IDENTIFIER), eq("string"), eq("string")))
        .thenReturn(1);
    //noinspection ResourceType
    when(resources.getString(1)).thenReturn(apiKey);
  }
}
