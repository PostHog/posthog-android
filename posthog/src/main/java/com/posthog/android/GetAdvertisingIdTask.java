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

import android.content.ContentResolver;
import android.content.Context;
import android.os.AsyncTask;
import android.provider.Settings.Secure;
import android.util.Pair;

import java.util.concurrent.CountDownLatch;

/**
 * An {@link AsyncTask} that fetches the advertising info and attaches it to the given {@link
 * PostHogContext} instance.
 */
class GetAdvertisingIdTask extends AsyncTask<Context, Void, Pair<String, Boolean>> {

  private final PostHogContext posthogContext;
  private final CountDownLatch latch;
  private final Logger logger;

  GetAdvertisingIdTask(PostHogContext posthogContext, CountDownLatch latch, Logger logger) {
    this.posthogContext = posthogContext;
    this.latch = latch;
    this.logger = logger;
  }

  private Pair<String, Boolean> getGooglePlayServicesAdvertisingID(Context context)
      throws Exception {
    Object advertisingInfo =
        Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            .getMethod("getAdvertisingIdInfo", Context.class)
            .invoke(null, context);
    Boolean isLimitAdCapturingEnabled =
        (Boolean)
            advertisingInfo
                .getClass()
                .getMethod("isLimitAdCapturingEnabled")
                .invoke(advertisingInfo);

    if (isLimitAdCapturingEnabled) {
      logger.debug(
          "Not collecting advertising ID because isLimitAdCapturingEnabled (Google Play Services) is true.");
      return Pair.create(null, false);
    }

    String advertisingId =
        (String) advertisingInfo.getClass().getMethod("getId").invoke(advertisingInfo);
    return Pair.create(advertisingId, true);
  }

  private Pair<String, Boolean> getAmazonFireAdvertisingID(Context context) throws Exception {
    ContentResolver contentResolver = context.getContentResolver();

    // Ref: http://prateeks.link/2uGs6bf
    // limit_ad_capturing != 0 indicates user wants to limit ad capturing.
    boolean limitAdCapturing = Secure.getInt(contentResolver, "limit_ad_capturing") != 0;

    if (limitAdCapturing) {
      logger.debug(
          "Not collecting advertising ID because limit_ad_capturing (Amazon Fire OS) is true.");
      return Pair.create(null, false);
    }

    String advertisingId = Secure.getString(contentResolver, "advertising_id");
    return Pair.create(advertisingId, true);
  }

  @Override
  protected Pair<String, Boolean> doInBackground(Context... contexts) {
    final Context context = contexts[0];
    try {
      return getGooglePlayServicesAdvertisingID(context);
    } catch (Exception e) {
      logger.error(e, "Unable to collect advertising ID from Google Play Services.");
    }
    try {
      return getAmazonFireAdvertisingID(context);
    } catch (Exception e) {
      logger.error(e, "Unable to collect advertising ID from Amazon Fire OS.");
    }
    logger.debug("Unable to collect advertising ID from Amazon Fire OS and Google Play Services.");
    return null;
  }

  @Override
  protected void onPostExecute(Pair<String, Boolean> info) {
    super.onPostExecute(info);

    try {
      if (info == null) {
        return;
      }
      posthogContext.putAdvertisingInfo(info.first, info.second);
    } finally {
      latch.countDown();
    }
  }
}
