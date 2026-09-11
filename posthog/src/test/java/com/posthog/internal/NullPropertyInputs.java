package com.posthog.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Java callers can put null into the existing Map<String, Object> API. */
public final class NullPropertyInputs {
    private NullPropertyInputs() {}

    public static Map<String, Object> properties() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("drop", null);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("test", null);
        properties.put("nested", empty);
        properties.put("items", Arrays.asList("1", null, 2, empty, Arrays.asList((Object) null)));
        properties.put("array", new Object[] {null, empty});
        properties.put("$set", empty);
        properties.put("$group_set", empty);
        properties.put("empty", "");
        properties.put("zero", 0);
        properties.put("enabled", false);
        properties.put("literal", "null");
        properties.put("literalUndefined", "undefined");
        properties.put("emptyArray", new Object[] {});
        return properties;
    }
}
