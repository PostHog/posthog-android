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

import static com.posthog.android.internal.Utils.NullableConcurrentHashMap;
import static com.posthog.android.internal.Utils.isNullOrEmpty;
import static java.util.Collections.unmodifiableMap;

import android.content.Context;
import com.posthog.android.internal.Private;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A class representing information about a user.
 *
 * <p>Traits can be anything you want, but some of them have semantic meaning and we treat them in
 * special ways. For example, whenever we see an email trait, we expect it to be the user's email
 * address. And we'll send this on to integrations that need an email, like Mailchimp. For that
 * reason, you should only use special traits for their intended purpose.
 *
 * <p>Traits are persisted to disk, and will be remembered between application and system reboots.
 */
public class Traits extends ValueMap {
  private static final String ANONYMOUS_ID_KEY = "anonymousId";
  private static final String DISTINCT_ID_KEY = "distinctId";

  /**
   * Create a new Traits instance with an anonymous ID. PostHog client can be called on any
   * thread, so this instance is thread safe.
   */
  static Traits create() {
    Traits traits = new Traits(new NullableConcurrentHashMap<String, Object>());
    traits.putAnonymousId(UUID.randomUUID().toString());
    return traits;
  }

  /** For deserialization from disk by {@link Traits.Cache}. */
  @Private
  Traits(Map<String, Object> delegate) {
    super(delegate);
  }

  // Public Constructor
  public Traits() {}

  public Traits(int initialCapacity) {
    super(initialCapacity);
  }

  public Traits unmodifiableCopy() {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(this);
    return new Traits(unmodifiableMap(map));
  }

  /**
   * Private API, users should call {@link com.posthog.android.PostHog#identify(String)}
   * instead. Note that this is unable to enforce it, users can easily do traits.put(id, ..);
   */
  Traits putDistinctId(String id) {
    return putValue(DISTINCT_ID_KEY, id);
  }

  public String distinctId() {
    return getString(DISTINCT_ID_KEY);
  }

  Traits putAnonymousId(String id) {
    return putValue(ANONYMOUS_ID_KEY, id);
  }

  public String anonymousId() {
    return getString(ANONYMOUS_ID_KEY);
  }

  /**
   * Returns the currentId the user is identified with. This could be the user id or the anonymous
   * ID.
   */
  public String currentId() {
    String distinctId = distinctId();
    return (isNullOrEmpty(distinctId)) ? anonymousId() : distinctId;
  }

  @Override
  public Traits putValue(String key, Object value) {
    super.putValue(key, value);
    return this;
  }

  static class Cache extends ValueMap.Cache<Traits> {

    // todo: remove. This is legacy behaviour from before we started namespacing the entire shared
    // preferences object and were namespacing keys instead.
    private static final String TRAITS_CACHE_PREFIX = "traits-";

    Cache(Context context, Cartographer cartographer, String tag) {
      super(context, cartographer, TRAITS_CACHE_PREFIX + tag, tag, Traits.class);
    }

    @Override
    public Traits create(Map<String, Object> map) {
      // PostHog client can be called on any thread, so this instance should be thread safe.
      return new Traits(new NullableConcurrentHashMap<>(map));
    }
  }
}
