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
package com.posthog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.Map;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TraitsTest {

  Traits traits;

  @Before
  public void setUp() {
    traits = Traits.create();
  }

  @Test
  public void newInvocationHasUniqueId() throws Exception {
    assertThat(traits).isNotSameAs(Traits.create());
  }

  @Test
  public void newInvocationHasNoUserId() throws Exception {
    assertThat(traits.distinctId()).isNull();
  }

  @Test
  public void publicConstructorGivesEmptyTraits() throws Exception {
    assertThat(new Traits()).hasSize(0);
  }

  @Test
  public void distinctIdOrAnonymousId() throws Exception {
    assertThat(new Traits().putUserId("foo").putAnonymousId("bar").currentId()) //
        .isEqualTo("foo");
    assertThat(new Traits().putUserId("foo").currentId()).isEqualTo("foo");
    assertThat(new Traits().putAnonymousId("bar").currentId()) //
        .isEqualTo("bar");
    assertThat(new Traits().currentId()).isNull();
  }

  @Test
  public void traitsAreMergedCorrectly() throws Exception {
    Traits traits1 =
        new Traits() //
            .putValue("age", 20)
            .putValue("avatar", "f2griffin")
            .putValue("description", "the first one")
            .putValue("lastName", "Griffin")
            .putValue("email", "griffin@gmail.com")
            .putValue("employees", 50);
    assertThat(traits1).hasSize(6);

    Traits traits2 =
        new Traits()
            .putValue("avatar", "griffin")
            .putValue("firstName", "Peter")
            .putValue("description", "the second one");
    assertThat(traits2).hasSize(3);

    traits1.putAll(traits2);
    assertThat(traits1)
        .hasSize(7)
        .contains(MapEntry.entry("avatar", "griffin"))
        .contains(MapEntry.entry("description", "the second one"))
        .contains(MapEntry.entry("email", "griffin@gmail.com"));
  }

  @Test
  public void copyReturnsSameMappings() {
    Traits copy = traits.unmodifiableCopy();

    assertThat(copy).hasSameSizeAs(traits).isNotSameAs(traits).isEqualTo(traits);
    for (Map.Entry<String, Object> entry : traits.entrySet()) {
      assertThat(copy).contains(MapEntry.entry(entry.getKey(), entry.getValue()));
    }
  }

  @Test
  public void copyIsImmutable() {
    Traits copy = traits.unmodifiableCopy();

    //noinspection EmptyCatchBlock
    try {
      copy.put("foo", "bar");
      fail("Inserting into copy should throw UnsupportedOperationException");
    } catch (UnsupportedOperationException ignored) {
    }
  }
}
