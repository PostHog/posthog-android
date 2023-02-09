package com.posthog.android.payloads;

import static com.posthog.android.payloads.CapturePayload.EVENT_KEY;
import static junit.framework.Assert.fail;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;

public class GroupPayloadTest {

  @Test
  public void emptyArgumentsThrows() {
    try {
      //noinspection CheckResult,ConstantConditions
      new GroupPayload.Builder().groupType(null);
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("groupType cannot be null or empty");
    }

    try {
      //noinspection CheckResult,ConstantConditions
      new GroupPayload.Builder().groupKey(null);
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("groupKey cannot be null or empty");
    }
  }
}
