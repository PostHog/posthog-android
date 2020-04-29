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

import android.annotation.SuppressLint;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import java.io.IOException;

/**
 * A {@link WearableListenerService} that listens for posthog events from a wear device.
 *
 * <p>Clients may subclass this and override {@link #getPostHog()} to provide custom instances of
 * {@link PostHog} client. Ideally, it should be the same instance as the client you're using to
 * capture events on the host Android device.
 */
@SuppressLint("Registered")
public class PhonePostHogListenerService extends WearableListenerService {

  final Cartographer cartographer = Cartographer.INSTANCE;

  @Override
  public void onMessageReceived(MessageEvent messageEvent) {
    super.onMessageReceived(messageEvent);

    if (WearPostHog.POSTHOG_PATH.equals(messageEvent.getPath())) {
      WearPayload wearPayload;
      try {
        wearPayload = new WearPayload(cartographer.fromJson(new String(messageEvent.getData())));
      } catch (IOException e) {
        getPostHog()
            .getLogger()
            .error(e, "Could not deserialize event %s", new String(messageEvent.getData()));
        return;
      }
      switch (wearPayload.type()) {
        case capture:
          WearCapturePayload wearCapturePayload = wearPayload.payload(WearCapturePayload.class);
          getPostHog().capture(wearCapturePayload.getEvent(), wearCapturePayload.getProperties(), null);
          break;
        case screen:
          WearScreenPayload wearScreenPayload = wearPayload.payload(WearScreenPayload.class);
          getPostHog()
              .screen(
                  wearScreenPayload.getName(),
                  wearScreenPayload.getProperties());
          break;
        default:
          throw new UnsupportedOperationException("Only capture/screen calls may be sent from Wear.");
      }
    }
  }

  public PostHog getPostHog() {
    return PostHog.with(this);
  }
}
