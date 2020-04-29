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

import org.junit.Before;
import org.junit.Test;

public class AliasPayloadTest {

  private AliasPayload.Builder builder;

  @Before
  public void setUp() {
    builder = new AliasPayload.Builder().alias("alias").distinctId("distinctId");
  }

  @Test
  public void alias() {
    AliasPayload payload = builder.alias("alias").build();
    assertThat(payload.alias()).isEqualTo("alias");
    assertThat(payload.properties()).containsEntry(AliasPayload.ALIAS_KEY, "alias");
  }

  @Test
  public void invalidPreviousIdThrows() {
    try {
      //noinspection CheckResult,ConstantConditions
      builder.alias(null);
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("alias cannot be null or empty");
    }

    try {
      //noinspection CheckResult
      builder.alias("");
      fail();
    } catch (NullPointerException e) {
      assertThat(e).hasMessage("alias cannot be null or empty");
    }
  }
}
