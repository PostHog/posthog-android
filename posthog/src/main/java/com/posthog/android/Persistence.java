package com.posthog.android;

import static java.util.Collections.unmodifiableMap;

import android.content.Context;

import com.posthog.android.internal.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistence layer is a cache map attached to a PostHog singleton instance
 */
public class Persistence extends ValueMap {
    public static final String ENABLED_FEATURE_FLAGS_KEY = "$enabled_feature_flags";

    static Persistence create() {
        Persistence persistence = new Persistence();
        return persistence;
    }

    public Persistence() {}

    public Persistence(int initialCapacity) {
        super(initialCapacity);
    }

    // For deserialization
    Persistence(Map<String, Object> delegate) {
        super(delegate);
    }

    public Persistence unmodifiableCopy() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>(this);
        return new Persistence(unmodifiableMap(map));
    }

    Persistence putEnabledFeatureFlags(Map featureFlags) {
        return putValue(ENABLED_FEATURE_FLAGS_KEY, featureFlags);
    }

    public ValueMap enabledFeatureFlags() {
        return getValueMap(ENABLED_FEATURE_FLAGS_KEY);
    }

    @Override
    public Persistence putValue(String key, Object value) {
        super.putValue(key, value);
        return this;
    }

    static class Cache extends ValueMap.Cache<Persistence> {

        Cache(Context context, Cartographer cartographer, String tag) {
            super(context, cartographer, tag, tag, Persistence.class);
        }

        @Override
        public Persistence create(Map<String, Object> map) {
            // PostHog client can be called on any thread, so this instance should be thread safe.
            return new Persistence(new Utils.NullableConcurrentHashMap<>(map));
        }
    }

}
