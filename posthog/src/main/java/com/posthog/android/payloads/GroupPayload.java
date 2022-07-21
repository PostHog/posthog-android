package com.posthog.android.payloads;

import static com.posthog.android.internal.Utils.assertNotNull;
import static com.posthog.android.internal.Utils.assertNotNullOrEmpty;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.posthog.android.Properties;
import com.posthog.android.internal.Private;

import java.util.Date;
import java.util.Map;

public class GroupPayload extends BasePayload {

  static final String GROUP_TYPE_KEY = "$group_type";
  static final String GROUP_KEY_KEY = "$group_key";
  static final String GROUP_SET_KEY = "$group_set";


  GroupPayload(
      @NonNull String messageId,
      @NonNull Date timestamp,
      @NonNull Map<String, Object> properties,
      @Nullable String distinctId,
      @NonNull String groupType,
      @NonNull String groupKey,
      @Nullable Map<String, Object> groupProperties) {
    super(Type.group, "$groupidentify", messageId, timestamp, properties, distinctId);
    put(GROUP_TYPE_KEY, groupType);
    put(GROUP_KEY_KEY, groupKey);
    put(GROUP_SET_KEY, groupProperties);
  }

  /**
   * Group type
   * ex: "company", "organization" 
   */
  @NonNull
  public String groupType() {
    return getString(GROUP_TYPE_KEY);
  }

  /**
   * Group key
   * ex: 
   */
  @NonNull
  public String groupKey() {
    return getString(GROUP_KEY_KEY);
  }


    /**
   * A dictionary of properties that give more information about the group. 
   */
  @Nullable
  public Map<String, Object> groupProperties() {
    return getValueMap(GROUP_SET_KEY, Properties.class);
  }

  @NonNull
  @Override
  public GroupPayload.Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent API for creating {@link GroupPayload} instances. */
  public static class Builder extends BasePayload.Builder<GroupPayload, Builder> {

    private String groupType;
    private String groupKey;
    private Map<String, Object> groupProperties;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(GroupPayload group) {
      super(group);
      groupType = group.groupType();
      groupKey = group.groupKey();
      groupProperties = group.properties();
    }

    @NonNull
    public Builder groupType(@NonNull String groupType) {
      this.groupType = assertNotNullOrEmpty(groupType, "groupType");
      return this;
    }

    @NonNull
    public Builder groupKey(@NonNull String groupKey) {
      this.groupKey = assertNotNullOrEmpty(groupKey, "groupKey");
      return this;
    }

    @NonNull
    public Builder groupProperties(@NonNull Map<String, Object> groupProperties) {
      this.groupProperties = assertNotNull(groupProperties, "groupProperties");
      return this;
    }

    @Override
    protected GroupPayload realBuild(
            @NonNull String messageId,
            @NonNull Date timestamp,
            @NonNull Map<String, Object> properties,
            @Nullable String distinctId) {
      return new GroupPayload(messageId, timestamp, properties, distinctId, groupType, groupKey, groupProperties);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
