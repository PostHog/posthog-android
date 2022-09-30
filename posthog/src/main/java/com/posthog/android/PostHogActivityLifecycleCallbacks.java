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
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class PostHogActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
  private PostHog posthog;
  private ExecutorService posthogExecutor;
  private Boolean shouldCaptureApplicationLifecycleEvents;
  private Boolean captureDeepLinks;
  private Boolean shouldRecordScreenViews;
  private PackageInfo packageInfo;

  private AtomicBoolean capturedApplicationLifecycleEvents;
  private AtomicInteger numberOfActivities;
  private AtomicBoolean isChangingActivityConfigurations;
  private AtomicBoolean firstLaunch;

  private PostHogActivityLifecycleCallbacks(
      PostHog posthog,
      ExecutorService posthogExecutor,
      Boolean shouldCaptureApplicationLifecycleEvents,
      Boolean captureDeepLinks,
      Boolean shouldRecordScreenViews,
      PackageInfo packageInfo) {
    this.capturedApplicationLifecycleEvents = new AtomicBoolean(false);
    this.numberOfActivities = new AtomicInteger(1);
    this.isChangingActivityConfigurations = new AtomicBoolean(false);
    this.firstLaunch = new AtomicBoolean(false);
    this.posthog = posthog;
    this.posthogExecutor = posthogExecutor;
    this.shouldCaptureApplicationLifecycleEvents = shouldCaptureApplicationLifecycleEvents;
    this.captureDeepLinks = captureDeepLinks;
    this.shouldRecordScreenViews = shouldRecordScreenViews;
    this.packageInfo = packageInfo;
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle bundle) {
    posthog.runOnMainThread(IntegrationOperation.onActivityCreated(activity, bundle));

    if (!capturedApplicationLifecycleEvents.getAndSet(true)
        && shouldCaptureApplicationLifecycleEvents) {
      numberOfActivities.set(0);
      firstLaunch.set(true);
      posthog.captureApplicationLifecycleEvents();

      if (!captureDeepLinks) {
        return;
      }

      Intent intent = activity.getIntent();
      if (intent == null || intent.getData() == null || !intent.getData().isHierarchical()) {
        return;
      }

      Properties properties = new Properties();
      Uri uri = intent.getData();
      for (String parameter : uri.getQueryParameterNames()) {
        String value = uri.getQueryParameter(parameter);
        if (value != null && !value.trim().isEmpty()) {
          properties.put(parameter, value);
        }
      }

      properties.put("url", uri.toString());
      posthog.capture("Deep Link Opened", properties);
    }
  }

  @Override
  public void onActivityStarted(Activity activity) {
    if (shouldRecordScreenViews) {
      posthog.recordScreenViews(activity);
    }
    posthog.runOnMainThread(IntegrationOperation.onActivityStarted(activity));
  }

  @Override
  public void onActivityResumed(Activity activity) {
    posthog.runOnMainThread(IntegrationOperation.onActivityResumed(activity));

    if (shouldCaptureApplicationLifecycleEvents
        && numberOfActivities.incrementAndGet() == 1
        && !isChangingActivityConfigurations.get()) {

      Properties properties = new Properties();
      if (firstLaunch.get()) {
        properties
            .putValue("version", packageInfo.versionName)
            .putValue("build", packageInfo.versionCode);
      }
      properties.putValue("from_background", !firstLaunch.getAndSet(false));
      posthog.capture("Application Opened", properties);
    }
  }

  @Override
  public void onActivityPaused(Activity activity) {
    posthog.runOnMainThread(IntegrationOperation.onActivityPaused(activity));
  }

  @Override
  public void onActivityStopped(Activity activity) {
    posthog.runOnMainThread(IntegrationOperation.onActivityStopped(activity));

    isChangingActivityConfigurations.set(activity.isChangingConfigurations());
    if (shouldCaptureApplicationLifecycleEvents
        && numberOfActivities.decrementAndGet() == 0
        && !isChangingActivityConfigurations.get()) {
      posthog.capture("Application Backgrounded");
    }
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
    posthog.runOnMainThread(IntegrationOperation.onActivitySaveInstanceState(activity, bundle));
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    posthog.runOnMainThread(IntegrationOperation.onActivityDestroyed(activity));
  }

  public static class Builder {
    private PostHog posthog;
    private ExecutorService posthogExecutor;
    private Boolean shouldCaptureApplicationLifecycleEvents;
    private Boolean captureDeepLinks;
    private Boolean shouldRecordScreenViews;
    private PackageInfo packageInfo;

    public Builder() {}

    public Builder posthog(PostHog posthog) {
      this.posthog = posthog;
      return this;
    }

    Builder posthogExecutor(ExecutorService posthogExecutor) {
      this.posthogExecutor = posthogExecutor;
      return this;
    }

    Builder shouldCaptureApplicationLifecycleEvents(Boolean shouldCaptureApplicationLifecycleEvents) {
      this.shouldCaptureApplicationLifecycleEvents = shouldCaptureApplicationLifecycleEvents;
      return this;
    }

    Builder captureDeepLinks(Boolean captureDeepLinks) {
      this.captureDeepLinks = captureDeepLinks;
      return this;
    }

    Builder shouldRecordScreenViews(Boolean shouldRecordScreenViews) {
      this.shouldRecordScreenViews = shouldRecordScreenViews;
      return this;
    }

    Builder packageInfo(PackageInfo packageInfo) {
      this.packageInfo = packageInfo;
      return this;
    }

    public PostHogActivityLifecycleCallbacks build() {
      return new PostHogActivityLifecycleCallbacks(
          posthog,
          posthogExecutor,
          shouldCaptureApplicationLifecycleEvents,
          captureDeepLinks,
          shouldRecordScreenViews,
          packageInfo);
    }
  }
}
