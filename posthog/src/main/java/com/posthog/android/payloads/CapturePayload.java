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
package com.posthog.android.payloads;

import static com.posthog.android.internal.Utils.assertNotNullOrEmpty;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.posthog.android.Properties;
import com.posthog.android.internal.Private;
import java.util.Date;
import java.util.Map;

public class CapturePayload extends BasePayload {

  @Private
  CapturePayload(
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId,
      @NonNull String event) {
    super(Type.capture, event, messageId, timestamp, properties, distinctId);
  }

  /**
   * The name of the event. We recommend using title case and past tense for event names, like
   * "Signed Up".
   */
  @NonNull
  public String event() {
    return getString(EVENT_KEY);
  }

  /**
   * A dictionary of properties that give more information about the event. We have a collection of
   * special properties that we recognize with semantic meaning. You can also add your own custom
   * properties.
   */
  @NonNull
  public Properties properties() {
    return getValueMap(PROPERTIES_KEY, Properties.class);
  }

  @Override
  public String toString() {
    return "CapturePayload{event=\"" + event() + "\"}";
  }

  @NonNull
  @Override
  public CapturePayload.Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent API for creating {@link CapturePayload} instances. */
  public static class Builder extends BasePayload.Builder<CapturePayload, Builder> {

    private String event;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(CapturePayload capture) {
      super(capture);
      event = capture.event();
    }

    @NonNull
    public Builder event(@NonNull String event) {
      this.event = assertNotNullOrEmpty(event, "event");
      return this;
    }

    @Override
    protected CapturePayload realBuild(
        @NonNull String messageId,
        @NonNull Date timestamp,
        @NonNull Map<String, Object> properties,
        String distinctId) {
      assertNotNullOrEmpty(event, "event");

      return new CapturePayload(messageId, timestamp, properties, distinctId, event);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
