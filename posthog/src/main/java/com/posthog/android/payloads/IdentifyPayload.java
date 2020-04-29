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

import static com.posthog.android.internal.Utils.assertNotNull;
import static com.posthog.android.internal.Utils.isNullOrEmpty;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.posthog.android.Properties;
import com.posthog.android.internal.Private;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class IdentifyPayload extends BasePayload {

  static final String USER_PROPERTIES_KEY = "$set";
  static final String ANON_DISTINCT_ID_KEY = "$anon_distinct_id";

  IdentifyPayload(
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId,
      @Nullable String anonymousId,
      @NonNull Map<String, Object> userProperties) {
    super(Type.identify, "$identify", messageId, timestamp, properties, distinctId);
    put(USER_PROPERTIES_KEY, userProperties);
    properties.put(ANON_DISTINCT_ID_KEY, anonymousId);
  }

  /**
   * A dictionary of properties you know about a user, for example email or name. We have a collection
   * of special traits that we recognize with semantic meaning, which you should always use when
   * recording that information. You can also add any custom traits that are specific to your
   * project to the dictionary, like friendCount or subscriptionType.
   */
  @NonNull
  public Properties userProperties() {
    return getValueMap(USER_PROPERTIES_KEY, Properties.class);
  }

  @Override
  public String toString() {
    return "IdentifyPayload{\"distinctId=\"" + distinctId() + "\"}";
  }

  @NonNull
  @Override
  public IdentifyPayload.Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent API for creating {@link IdentifyPayload} instances. */
  public static class Builder extends BasePayload.Builder<IdentifyPayload, Builder> {

    private Map<String, Object> userProperties;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(IdentifyPayload identify) {
      super(identify);
      userProperties = identify.userProperties();
    }

    @NonNull
    public Builder userProperties(@NonNull Map<String, ?> userProperties) {
      assertNotNull(userProperties, "userProperties");
      this.userProperties = Collections.unmodifiableMap(new LinkedHashMap<>(userProperties));
      return this;
    }

    @Override
    IdentifyPayload realBuild(
        @NonNull String messageId,
        @NonNull Date timestamp,
        @NonNull Map<String, Object> properties,
        String distinctId) {
      return new IdentifyPayload(messageId, timestamp, properties, distinctId, anonymousId, userProperties);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
