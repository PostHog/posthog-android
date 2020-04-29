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

import static org.assertj.core.api.Assertions.assertThat;
import static org.robolectric.annotation.Config.NONE;

import android.content.ContentResolver;
import android.content.Context;
import android.os.AsyncTask;
import android.provider.Settings.Secure;
import android.util.Pair;

import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = NONE)
public class GetAdvertisingIdTaskTest {
  @Test
  public void getAdvertisingId() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    PostHogContext context =
        PostHogContext.create(RuntimeEnvironment.application, new Traits(), true);
    AsyncTask<Context, Void, Pair<String, Boolean>> task =
        new GetAdvertisingIdTask(context, latch, Logger.with(PostHog.LogLevel.VERBOSE));
    task.execute(RuntimeEnvironment.application);
    latch.await();
    assertThat(context).doesNotContainKey("$device_advertising_id");
  }

  @Test
  public void getAdvertisingIdAmazonFireOSLimitAdCapturing1() throws Exception {
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_capturing", 1);
    CountDownLatch latch = new CountDownLatch(1);

    PostHogContext posthogContext =
        PostHogContext.create(RuntimeEnvironment.application, new Traits(),true);

    AsyncTask<Context, Void, Pair<String, Boolean>> task =
        new GetAdvertisingIdTask(posthogContext, latch, Logger.with(PostHog.LogLevel.VERBOSE));
    task.execute(context);
    latch.await();

    assertThat(posthogContext).doesNotContainKey("$device_advertising_id");
    assertThat(posthogContext).containsEntry("$device_ad_capturing_enabled", false);
  }

  @Test
  public void getAdvertisingIdAmazonFireOSLimitAdCapturing0() throws Exception {
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_capturing", 0);
    Secure.putString(contentResolver, "advertising_id", "df07c7dc-cea7-4a89-b328-810ff5acb15d");

    CountDownLatch latch = new CountDownLatch(1);

    PostHogContext posthogContext =
        PostHogContext.create(RuntimeEnvironment.application, new Traits(),true);

    AsyncTask<Context, Void, Pair<String, Boolean>> task =
        new GetAdvertisingIdTask(posthogContext, latch, Logger.with(PostHog.LogLevel.VERBOSE));
    task.execute(context);
    latch.await();

    assertThat(posthogContext)
        .containsEntry("$device_advertising_id", "df07c7dc-cea7-4a89-b328-810ff5acb15d");
    assertThat(posthogContext).containsEntry("$device_ad_capturing_enabled", true);
  }
}
