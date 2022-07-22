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

import static com.posthog.android.internal.Utils.isNullOrEmpty;
import static com.posthog.android.internal.Utils.NullableConcurrentHashMap;
import static java.util.Collections.unmodifiableMap;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;



/**
 * Properties are a dictionary of free-form information to attach to specific events.
 *
 * <p>Just like properties, we also accept some properties with semantic meaning, and you should only
 * ever use these property names for that purpose.
 */
public class Properties extends ValueMap {
  private static final String ANONYMOUS_ID_KEY = "anonymousId";
  private static final String DISTINCT_ID_KEY = "distinctId";
  private static final String GROUPS_KEY = "groups";
  private static final String ACTIVE_FEATURE_FLAGS_KEY = "$active_feature_flags";
  private static final String FEATURE_FLAG_KEY_PREFIX = "$feature/";
  /**
   * Create a new Properties instance with an anonymous ID. PostHog client can be called on any
   * thread, so this instance is thread safe.
   */
  static Properties create() {
    Properties properties = new Properties(new NullableConcurrentHashMap<String, Object>());
    properties.putAnonymousId(UUID.randomUUID().toString());
    return properties;
  }

  public Properties() {}

  public Properties(int initialCapacity) {
    super(initialCapacity);
  }

  // For deserialization
  Properties(Map<String, Object> delegate) {
    super(delegate);
  }

  public Properties unmodifiableCopy() {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(this);
    return new Properties(unmodifiableMap(map));
  }
  
  /**
   * Private API, users should call {@link com.posthog.android.PostHog#identify(String)}
   * instead. Note that this is unable to enforce it, users can easily do properties.put(id, ..);
   */
  Properties putDistinctId(String id) {
    return putValue(DISTINCT_ID_KEY, id);
  }

  public String distinctId() {
    return getString(DISTINCT_ID_KEY);
  }

  Properties putAnonymousId(String id) {
    return putValue(ANONYMOUS_ID_KEY, id);
  }

  public String anonymousId() {
    return getString(ANONYMOUS_ID_KEY);
  }

  Properties putGroups(ValueMap groups) {
    return putValue(GROUPS_KEY, groups);
  }

  public ValueMap groups() {
    return getValueMap(GROUPS_KEY);
  }

  Properties putFeatureFlag(String flagKey, Object flagValue) {
    return putValue(String.format("%s%s", FEATURE_FLAG_KEY_PREFIX, flagKey), flagValue);
  }

  public String featureFlag(String flagKey) {
    return getString(String.format("%s%s", FEATURE_FLAG_KEY_PREFIX, flagKey));
  }

  Properties putActiveFeatureFlags(List<String> flagKeys) {
    return putValue(ACTIVE_FEATURE_FLAGS_KEY, flagKeys);
  }

  public ArrayList<String> activeFeatureFlags(String flagKey) {
    return (ArrayList<String>) get(ACTIVE_FEATURE_FLAGS_KEY);
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
  public Properties putValue(String key, Object value) {
    super.putValue(key, value);
    return this;
  }

  static class Cache extends ValueMap.Cache<Properties> {

    Cache(Context context, Cartographer cartographer, String tag) {
      super(context, cartographer, tag, tag, Properties.class);
    }

    @Override
    public Properties create(Map<String, Object> map) {
      // PostHog client can be called on any thread, so this instance should be thread safe.
      return new Properties(new NullableConcurrentHashMap<>(map));
    }
  }

}
