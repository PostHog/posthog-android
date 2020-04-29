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

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.NonNull;
import com.posthog.android.payloads.AliasPayload;
import com.posthog.android.payloads.CapturePayload;
import com.posthog.android.payloads.IdentifyPayload;
import com.posthog.android.payloads.ScreenPayload;

/**
 * Converts messages to a format an integration understands, and calls those methods.
 *
 * @param <T> The type of the backing instance.
 */
public abstract class Integration<T> {

  public interface Factory {

    /**
     * Attempts to create an adapter. This returns the adapter if one was
     * created, or null if this factory isn't capable of creating such an adapter.
     */
    Integration<?> create(PostHog posthog);

    /** The key for which this factory can create an {@link Integration}. */
    @NonNull
    String key();
  }

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityStarted(Activity activity) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityResumed(Activity activity) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityPaused(Activity activity) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityStopped(Activity activity) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

  /** @see android.app.Application.ActivityLifecycleCallbacks */
  public void onActivityDestroyed(Activity activity) {}

  /**
   * @see PostHog#identify(String, com.posthog.Traits, com.posthog.Options)
   */
  public void identify(IdentifyPayload identify) {}

  /**
   * @see PostHog#capture(String, com.posthog.android.Properties, com.posthog.Options)
   */
  public void capture(CapturePayload capture) {}

  /** @see PostHog#alias(String, com.posthog.Options) */
  public void alias(AliasPayload alias) {}

  /**
   * @see PostHog#screen(String, com.posthog.android.Properties,
   *     com.posthog.Options)
   */
  public void screen(ScreenPayload screen) {}

  /** @see PostHog#flush() */
  public void flush() {}

  /** @see PostHog#reset() */
  public void reset() {}
}
