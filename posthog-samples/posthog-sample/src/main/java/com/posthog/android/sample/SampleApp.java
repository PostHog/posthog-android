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
package com.posthog.android.sample;

import android.app.Application;
import android.util.Log;
import com.posthog.android.PostHog;
import uk.co.chrisjenx.calligraphy.CalligraphyConfig;

public class SampleApp extends Application {

  private static final String POSTHOG_API_KEY = "8jVz0YZ2YPtP7eL1I5l5RQIp-WcuFeD3pZO8c0YDMx4";

  @Override
  public void onCreate() {
    super.onCreate();

    CalligraphyConfig.initDefault(
        new CalligraphyConfig.Builder()
            .setDefaultFontPath("fonts/CircularStd-Book.otf")
            .setFontAttrId(R.attr.fontPath)
            .build());

    // Initialize a new instance of the PostHog client.
    PostHog.Builder builder =
        new PostHog.Builder(this, POSTHOG_API_KEY, "http://d37f3802.ngrok.io")
            .captureApplicationLifecycleEvents()
            .recordScreenViews();

    // Set the initialized instance as a globally accessible instance.
    PostHog.setSingletonInstance(builder.build());

    // Now anytime you call PostHog.with, the custom instance will be returned.
    PostHog posthog = PostHog.with(this);
  }
}
