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
import static com.posthog.android.internal.Utils.assertNotNullOrEmpty;
import static com.posthog.android.internal.Utils.isNullOrEmpty;
import static com.posthog.android.internal.Utils.parseISO8601Date;
import static com.posthog.android.internal.Utils.toISO8601String;

import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.posthog.android.ValueMap;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A payload object that will be sent to the server. Clients will not decode instances of this
 * directly, but through one if it's subclasses.
 */
public abstract class BasePayload extends ValueMap {

  static final String TYPE_KEY = "type";
  static final String EVENT_KEY = "event";
  static final String MESSAGE_ID = "message_id";
  static final String PROPERTIES_KEY = "properties";
  static final String TIMESTAMP_KEY = "timestamp";
  static final String DISTINCT_ID_KEY = "distinct_id";

  BasePayload(
      @NonNull Type type,
      @NonNull String event,
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId) {
    put(TYPE_KEY, type);
    put(EVENT_KEY, event);
    put(MESSAGE_ID, messageId);
    put(TIMESTAMP_KEY, toISO8601String(timestamp));
    put(PROPERTIES_KEY, properties);
    put(DISTINCT_ID_KEY, distinctId);
  }

  /** The type of message. */
  @NonNull
  public Type type() {
    return getEnum(Type.class, TYPE_KEY);
  }

  /**
   * The user ID is an identifier that unique identifies the user in your database. Ideally it
   * should not be an email address, because emails can change, whereas a database ID can't.
   */
  @Nullable
  public String distinctId() {
    return getString(DISTINCT_ID_KEY);
  }

  @Nullable
  public String event() {
    return getString(EVENT_KEY);
  }

  /** A randomly generated unique id for this message. */
  @NonNull
  public String messageId() {
    return getString(MESSAGE_ID);
  }

  /**
   * Set a timestamp the event occurred.
   *
   * <p>This library will automatically create and attach a timestamp to all events.
   */
  @Nullable
  public Date timestamp() {
    // It's unclear if this will ever be null. So we're being safe.
    String timestamp = getString(TIMESTAMP_KEY);
    if (isNullOrEmpty(timestamp)) {
      return null;
    }
    return parseISO8601Date(timestamp);
  }

  /**
   * The context is a dictionary of extra information that provides useful context about a message,
   * for example ip address or locale.
   */
  public ValueMap properties() {
    return getValueMap(PROPERTIES_KEY, ValueMap.class);
  }

  @Override
  public BasePayload putValue(String key, Object value) {
    super.putValue(key, value);
    return this;
  }

  @NonNull
  public abstract Builder toBuilder();

  /** @see #TYPE_KEY */
  public enum Type {
    alias,
    identify,
    screen,
    capture,
    group
  }

  public abstract static class Builder<P extends BasePayload, B extends Builder> {
    private String distinctId;
    private String messageId;
    private Date timestamp;
    private Map<String, Object> properties;

    // transient
    protected String anonymousId;
    private Map<String, Object> context;

    Builder() {
      // Empty constructor.
    }

    Builder(BasePayload payload) {
      messageId = payload.messageId();
      timestamp = payload.timestamp();
      properties = payload.properties();
      distinctId = payload.distinctId();
    }

    /**
     * The Message ID is a unique identifier for each message. If not provided, one will be
     * generated for you. This ID is typically used for deduping - messages with the same IDs as
     * previous events may be dropped.
     */
    @NonNull
    public B messageId(@NonNull String messageId) {
      assertNotNullOrEmpty(messageId, "messageId");
      this.messageId = messageId;
      return self();
    }

    /**
     * Set a timestamp for the event. By default, the current timestamp is used, but you may
     * override it for historical import.
     *
     * <p>This library will automatically create and attach a timestamp to all events.
     */
    @NonNull
    public B timestamp(@NonNull Date timestamp) {
      assertNotNull(timestamp, "timestamp");
      this.timestamp = timestamp;
      return self();
    }

    /**
     * A dictionary of properties that give more information about the event. We have a collection of
     * special properties that we recognize with semantic meaning. You can also add your own custom
     * properties.
     */
    @NonNull
    public B properties(@NonNull Map<String, ?> properties) {
      assertNotNull(properties, "properties");
      this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
      return self();
    }

    /**
     * The User ID is a persistent unique identifier for a user (such as a database ID).
     */
    @NonNull
    public B distinctId(@NonNull String distinctId) {
      this.distinctId = assertNotNullOrEmpty(distinctId, "distinctId");
      return self();
    }

    /**
     * Set a map of information about the state of the device. You can add any custom data to the
     * context dictionary that you'd like to have access to in the raw logs.
     *
     * <p>Some keys in the context dictionary have semantic meaning and will be collected for you
     * automatically, depending on the library you send data from. Some keys, such as location and
     * speed need to be manually entered.
     */
    @NonNull
    public B context(@NonNull Map<String, ?> context) {
      assertNotNull(context, "context");
      this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
      return self();
    }

    /**
     * The Anonymous ID is a pseudo-unique substitute for a User ID, for cases when you don't have
     * an absolutely unique identifier.
     */
    @NonNull
    public B anonymousId(@NonNull String anonymousId) {
      this.anonymousId = assertNotNullOrEmpty(anonymousId, "anonymousId");
      return self();
    }

    abstract P realBuild(
        @NonNull String messageId,
        @NonNull Date timestamp,
        @NonNull Map<String, Object> properties,
        @Nullable String distinctId);

    abstract B self();

    /** Create a {@link BasePayload} instance. */
    @CheckResult
    @NonNull
    public P build() {
      if (isNullOrEmpty(distinctId) && isNullOrEmpty(anonymousId)) {
        throw new NullPointerException("either distinctId or anonymousId is required");
      }

      if (isNullOrEmpty(messageId)) {
        messageId = UUID.randomUUID().toString();
      }

      if (timestamp == null) {
        timestamp = new Date();
      }

      if (isNullOrEmpty(properties)) {
        properties = Collections.emptyMap();
      }

      if (isNullOrEmpty(context)) {
        context = Collections.emptyMap();
      }

      // merge context into properties
      Map<String, Object> finalProperties = new LinkedHashMap<>(context);
      finalProperties.putAll(properties);

      // select distinct id or anonymous id
      String distinctUserId = distinctId;
      if (isNullOrEmpty(distinctUserId)) {
        distinctUserId = anonymousId;
      }

      return realBuild(messageId, timestamp, finalProperties, distinctUserId);
    }
  }
}
