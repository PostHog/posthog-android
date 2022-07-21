package com.posthog.android.payloads;

import static com.posthog.android.payloads.CapturePayload.EVENT_KEY;
import static junit.framework.Assert.fail;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;

public class GroupPayloadTest {

  private GroupPayload.Builder builder;

  @Before
  public void setUp() {
    builder = new GroupPayload.Builder().groupType("group-type").groupKey("group-key");
  }

  @Test
  public void emptyArgumentsThrows() {
    try {
      //noinspection CheckResult,ConstantConditions
      new GroupPayload.Builder();
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("group type cannot be null or empty");
    }

    try {
      //noinspection CheckResult,ConstantConditions
      new GroupPayload.Builder().groupType("group-type");
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("group key cannot be null or empty");
    }
  }
}
