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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.posthog.android.payloads.BasePayload.Builder;
import com.posthog.android.payloads.BasePayload.Type;
import java.util.Date;
import java.util.List;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Test;

public class BasePayloadTest {

  private List<Builder<? extends BasePayload, ? extends Builder<?, ?>>> builders;

  @Before
  public void setUp() {
    builders =
        ImmutableList.of(
            new AliasPayload.Builder().alias("new_alias"),
            new CapturePayload.Builder().event("event"),
            new ScreenPayload.Builder().name("name"),
            new IdentifyPayload.Builder().userProperties(ImmutableMap.<String, Object>of("foo", "bar")));
  }

  @Test
  public void nullTimestampThrows() {
    for (int i = 1; i < builders.size(); i++) {
      Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.timestamp(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("timestamp == null");
      }
    }
  }

  @Test
  public void timestamp() {
    Date timestamp = new Date();
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").timestamp(timestamp).build();
      assertThat(payload.timestamp()).isEqualTo(timestamp);
      assertThat(payload).containsKey(BasePayload.TIMESTAMP_KEY);
    }
  }

  @Test
  public void type() {
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").build();
      assertThat(payload.type())
          .isIn(Type.alias, Type.capture, Type.screen, Type.identify);
      assertThat(payload).containsKey(BasePayload.TYPE_KEY);
    }
  }

  @Test
  public void invalidDistinctIdThrows() {
    for (int i = 1; i < builders.size(); i++) {
      Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.distinctId(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("distinctId cannot be null or empty");
      }

      try {
        //noinspection CheckResult
        builder.distinctId("");
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("distinctId cannot be null or empty");
      }
    }
  }

  @Test
  public void anonymousId() {
    for (Builder builder : builders) {
      BasePayload payload = builder.anonymousId("anonymous_id").build();
      assertThat(payload.distinctId()).isEqualTo("anonymous_id");
      assertThat(payload).containsEntry(BasePayload.DISTINCT_ID_KEY, "anonymous_id");
    }
  }

  @Test
  public void distinctId() {
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").build();
      assertThat(payload.distinctId()).isEqualTo("distinct_id");
      assertThat(payload).containsEntry(BasePayload.DISTINCT_ID_KEY, "distinct_id");
    }
  }

  @Test
  public void anonymousAndDistinctId() {
    for (Builder builder : builders) {
      BasePayload payload = builder.anonymousId("anonymous_id").distinctId("distinct_id").build();
      assertThat(payload.distinctId()).isEqualTo("distinct_id");
      assertThat(payload).containsEntry(BasePayload.DISTINCT_ID_KEY, "distinct_id");
    }
  }

  @Test
  public void requiresDistinctIdOrAnonymousId() {
    for (int i = 1; i < builders.size(); i++) {
      Builder builder = builders.get(i);
      try {
        //noinspection CheckResult
        builder.build();
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("either distinctId or anonymousId is required");
      }
    }
  }

  @Test
  public void invalidMessageIdThrows() {
    for (int i = 1; i < builders.size(); i++) {
      Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.messageId(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("messageId cannot be null or empty");
      }

      try {
        //noinspection CheckResult
        builder.messageId("");
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("messageId cannot be null or empty");
      }
    }
  }

  @Test
  public void messageId() {
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").messageId("message_id").build();
      assertThat(payload.messageId()).isEqualTo("message_id");
      assertThat(payload).containsEntry(BasePayload.MESSAGE_ID, "message_id");
    }
  }

  @Test
  public void messageIdIsGenerated() {
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").build();
      assertThat(payload.messageId()).isNotEmpty();
      assertThat(payload).containsKey(BasePayload.MESSAGE_ID);
    }
  }

  @Test
  public void nullContextThrows() {
    for (int i = 1; i < builders.size(); i++) {
      Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.context(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("context == null");
      }
    }
  }

  @Test
  public void context() {
    for (Builder builder : builders) {
      BasePayload payload =
          builder.distinctId("distinct_id").context(ImmutableMap.of("foo", "bar")).build();
      assertThat(payload.properties()).contains(MapEntry.entry("foo", "bar"));
    }
  }

  @Test
  public void putValue() {
    for (Builder builder : builders) {
      BasePayload payload = builder.distinctId("distinct_id").build().putValue("foo", "bar");
      assertThat(payload).containsEntry("foo", "bar");
    }
  }

  @Test
  public void builderCopy() {
    for (Builder builder : builders) {
      BasePayload payload =
          builder.distinctId("distinct_id").build().toBuilder().distinctId("a_new_distinct_id").build();
      assertThat(payload.distinctId()).isEqualTo("a_new_distinct_id");
    }
  }
}
