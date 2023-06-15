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
import com.posthog.android.internal.Private;
import java.util.Date;
import java.util.Map;

public class AliasPayload extends BasePayload {

  static final String ALIAS_KEY = "alias";

  @Private
  AliasPayload(
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId,
      @NonNull String alias) {
    super(Type.alias, "$create_alias", messageId, timestamp, properties, distinctId);

    properties.put(DISTINCT_ID_KEY, distinctId);
    properties.put(ALIAS_KEY, alias);
  }

  /**
   * The previous ID for the user that you want to alias from, that you previously called identify
   * with as their user ID, or the anonymous ID if you haven't identified the user yet.
   */
  public String alias() {
    return properties().getString(ALIAS_KEY);
  }

  @Override
  public String toString() {
    return "AliasPayload{distinctId=\"" + distinctId() + ",alias=\"" + alias() + "\"}";
  }

  @NonNull
  @Override
  public AliasPayload.Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent API for creating {@link AliasPayload} instances. */
  public static final class Builder extends BasePayload.Builder<AliasPayload, Builder> {

    private String alias;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(AliasPayload alias) {
      super(alias);
      this.alias = alias.alias();
    }

    @NonNull
    public Builder alias(@NonNull String alias) {
      this.alias = assertNotNullOrEmpty(alias, "alias");
      return this;
    }

    @Override
    protected AliasPayload realBuild(
        @NonNull String messageId,
        @NonNull Date timestamp,
        @NonNull Map<String, Object> properties,
        @Nullable String distinctId) {
      assertNotNullOrEmpty(alias, "alias");

      return new AliasPayload(messageId, timestamp, properties, distinctId, alias);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
