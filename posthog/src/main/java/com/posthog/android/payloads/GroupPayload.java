package com.posthog.android.payloads;

import static com.posthog.android.internal.Utils.assertNotNull;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.posthog.android.Properties;
import com.posthog.android.internal.Private;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class GroupPayload extends BasePayload {

//   static final String USER_PROPERTIES_KEY = "$set";
//   static final String ANON_DISTINCT_ID_KEY = "$anon_distinct_id";

  static final String GROUP_TYPE_KEY = "$group_type";
  static final String GROUP_KEY_KEY = "$group_key";
  static final String GROUP_SET_KEY = "$group_set";


  GroupPayload(
      @NonNull String groupType,
      @NonNull String groupKey,
      @Nullable Map<String, Object> properties) {
    super(Type.group, groupType, groupKey, properties)
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
  public Properties properties() {
    return getValueMap(PROPERTIES_KEY, Properties.class);
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
    private Map<String, Object> properties;

    public Builder() {
      // Empty constructor.
    }

    @Private
    Builder(GroupPayload group) {
      super(group);
      groupType = group.group_type()
      groupKey = group.group_key()
      properties = group.properties()
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


    @Override
    GroupPayload realBuild(
        @NonNull String groupType,
        @NonNull String groupKey,
        @Nullable Map<String, Object> properties,
        ) {
      return new GroupPayload(groupType, groupKey, properties);
    }

    @Override
    Builder self() {
      return this;
    }
  }
}
