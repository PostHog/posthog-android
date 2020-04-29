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
package com.posthog;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static com.posthog.Utils.createContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.Config.NONE;

import android.content.Context;
import android.net.ConnectivityManager;
import com.google.common.collect.ImmutableMap;
import com.posthog.core.BuildConfig;
import java.util.Map;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = NONE)
public class PostHogContextTest {

  private PostHogContext context;
  private Traits traits;

  @Before
  public void setUp() {
    traits = Traits.create();
    context = createContext();
  }

  @Test
  public void create() {
    context = PostHogContext.create(RuntimeEnvironment.application, traits, true);
    assertThat(context) //
        .containsKey("$locale") //
        .containsEntry("$user_agent", "undefined") //
        .containsKey("$timezone") // value depends on where the tests are run
        .containsEntry("$app_name", "org.robolectric.default")
        .containsEntry("$app_version", "undefined")
        .containsEntry("$app_namespace", "org.robolectric.default")
        .containsEntry("$app_build", "0")
        .containsEntry("$device_id", "unknown")
        .containsEntry("$device_manufacturer", "unknown")
        .containsEntry("$device_model", "unknown")
        .containsEntry("$device_name", "unknown")
        .containsEntry("$lib", "posthog-android")
        .containsEntry("$lib_version", BuildConfig.VERSION_NAME)
        .containsEntry("$os_name", "Android") //
        .containsEntry("$os_version", "4.1.2_r1")
        .containsEntry("$screen_density", 1.5f) //
        .containsEntry("$screen_width", 480) //
        .containsEntry("$screen_height", 800)
        .doesNotContainKey("$network_bluetooth") //
        .doesNotContainKey("$network_carrier") //
        .doesNotContainKey("$network_cellular") //
        .doesNotContainKey("$network_wifi"); //
  }

  @Test
  public void createWithoutDeviceIdCollection() {
    context = PostHogContext.create(RuntimeEnvironment.application, traits, false);

    assertThat(context) //
        .containsEntry("$device_id", traits.anonymousId())
        .containsEntry("$device_manufacturer", "unknown")
        .containsEntry("$device_model", "unknown")
        .containsEntry("$device_name", "unknown");
  }

  @Test
  public void copyReturnsSameMappings() {
    PostHogContext copy = context.unmodifiableCopy();

    assertThat(copy).hasSameSizeAs(context).isNotSameAs(context).isEqualTo(context);
    for (Map.Entry<String, Object> entry : context.entrySet()) {
      assertThat(copy).contains(MapEntry.entry(entry.getKey(), entry.getValue()));
    }
  }

  @Test
  public void copyIsImmutable() {
    PostHogContext copy = context.unmodifiableCopy();

    //noinspection EmptyCatchBlock
    try {
      copy.put("foo", "bar");
      fail("Inserting into copy should throw UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {

    }
  }

  @Test
  public void device() {
    context.putAdvertisingInfo("adId", true);
    assertThat(context).containsEntry("$device_advertising_id", "adId");
    assertThat(context).containsEntry("$device_ad_capturing_enabled", true);
  }

  @Test
  public void network() {
    Context application = mock(Context.class);
    ConnectivityManager manager = mock(ConnectivityManager.class);
    when(application.getSystemService(CONNECTIVITY_SERVICE)).thenReturn(manager);
    context.putNetwork(application);

    assertThat(context)
        .containsEntry("$network_wifi", false)
        .containsEntry("$network_carrier", "unknown")
        .containsEntry("$network_bluetooth", false)
        .containsEntry("$network_cellular", false);
  }
}
