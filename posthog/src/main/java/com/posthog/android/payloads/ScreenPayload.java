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

import static com.posthog.android.internal.Utils.isNullOrEmpty;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.posthog.android.Properties;
import com.posthog.android.internal.Private;
import java.util.Date;
import java.util.Map;

public class ScreenPayload extends BasePayload {

  static final String NAME_KEY = "$screen_name";

  @Private
  ScreenPayload(
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId,
      @Nullable String name) {

    super(Type.screen, "$screen", messageId, timestamp, properties, distinctId);

    if (!isNullOrEmpty(name)) {
      properties.put(NAME_KEY, name);
    }
  }

  /** The name of the page or screen. We recommend using title case, like "About". */
  @Nullable
  public String name() {
    return properties().getString(NAME_KEY);
  }

  /** The page and screen methods also take a properties dictionary, just like capture. */
  @NonNull
  public Properties properties() {
    return getValueMap(PROPERTIES_KEY, Properties.class);
  }

  @Override
  public String toString() {
    return "ScreenPayload{name=\"" + name() + "\"}";
  }

  @NonNull
  @Override
  public ScreenPayload.Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent API for creating {@link ScreenPayload} instances. */
  public static class Builder extends BasePayload.Builder<ScreenPayload, Builder> {

    private String name;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(ScreenPayload screen) {
      super(screen);
      name = screen.name();
    }

    @NonNull
    public Builder name(@Nullable String name) {
      this.name = name;
      return this;
    }

    @Override
    protected ScreenPayload realBuild(
        @NonNull String messageId,
        @NonNull Date timestamp,
        @NonNull Map<String, Object> properties,
        @Nullable String distinctId) {

      if (isNullOrEmpty(name)) {
        throw new NullPointerException("name is required");
      }

      return new ScreenPayload(
          messageId,
          timestamp,
          properties,
          distinctId,
          name);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
