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
package com.posthog.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.data.MapEntry;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ValueMapTest {

  ValueMap valueMap;
  Cartographer cartographer;

  @Before
  public void setUp() {
    initMocks(this);
    valueMap = new ValueMap();
    cartographer = Cartographer.INSTANCE;
  }

  @Test
  public void disallowsNullMap() throws Exception {
    try {
      new ValueMap(null);
      fail("Null Map should throw exception.");
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Test
  public void emptyMap() throws Exception {
    assertThat(valueMap).hasSize(0).isEmpty();
  }

  @SuppressWarnings({"ResultOfMethodCallIgnored", "SuspiciousMethodCalls"}) //
  @Test
  public void methodsAreForwardedCorrectly() {
    // todo: don't mock!
    Map<String, Object> delegate = spy(new LinkedHashMap<String, Object>());
    Object object = "foo";

    valueMap = new ValueMap(delegate);

    valueMap.clear();
    verify(delegate).clear();

    valueMap.containsKey(object);
    verify(delegate).containsKey(object);

    valueMap.entrySet();
    verify(delegate).entrySet();

    valueMap.get(object);
    verify(delegate).get(object);

    valueMap.isEmpty();
    verify(delegate).isEmpty();

    valueMap.keySet();
    verify(delegate).keySet();

    valueMap.put("foo", object);
    verify(delegate).put("foo", object);

    Map<String, Object> map = new LinkedHashMap<>();
    valueMap.putAll(map);
    verify(delegate).putAll(map);

    valueMap.remove(object);
    verify(delegate).remove(object);

    valueMap.size();
    verify(delegate).size();

    valueMap.values();
    verify(delegate).values();

    valueMap.putValue("bar", object);
    verify(delegate).put("bar", object);
  }

  @Test
  public void simpleConversions() throws Exception {
    String stringPi = String.valueOf(Math.PI);

    valueMap.put("double_pi", Math.PI);
    assertThat(valueMap.getString("double_pi")).isEqualTo(stringPi);

    valueMap.put("string_pi", stringPi);
    assertThat(valueMap.getDouble("string_pi", 0)).isEqualTo(Math.PI);
  }

  @Test
  public void enumDeserialization() throws Exception {
    valueMap.put("value1", MyEnum.VALUE1);
    valueMap.put("value2", MyEnum.VALUE2);
    String json = cartographer.toJson(valueMap);
    // todo: the ordering may be different on different versions of Java
    assertThat(json)
        .isIn(
            "{\"value2\":\"VALUE2\",\"value1\":\"VALUE1\"}",
            "{\"value1\":\"VALUE1\",\"value2\":\"VALUE2\"}");

    valueMap = new ValueMap(cartographer.fromJson("{\"value1\":\"VALUE1\",\"value2\":\"VALUE2\"}"));
    assertThat(valueMap) //
        .contains(MapEntry.entry("value1", "VALUE1")) //
        .contains(MapEntry.entry("value2", "VALUE2"));
    assertThat(valueMap.getEnum(MyEnum.class, "value1")).isEqualTo(MyEnum.VALUE1);
    assertThat(valueMap.getEnum(MyEnum.class, "value2")).isEqualTo(MyEnum.VALUE2);
  }

  @Test
  public void allowsNullValues() {
    valueMap.put(null, "foo");
    valueMap.put("foo", null);
  }

  @Test
  public void nestedMaps() throws Exception {
    ValueMap nested = new ValueMap();
    nested.put("value", "box");
    valueMap.put("nested", nested);

    assertThat(valueMap).hasSize(1).contains(MapEntry.entry("nested", nested));
    assertThat(cartographer.toJson(valueMap)).isEqualTo("{\"nested\":{\"value\":\"box\"}}");

    valueMap = new ValueMap(cartographer.fromJson("{\"nested\":{\"value\":\"box\"}}"));
    assertThat(valueMap).hasSize(1).contains(MapEntry.entry("nested", nested));
  }

  @Test
  public void toJsonObjectWithNullValue() throws Exception {
    valueMap.put("foo", null);

    JSONObject jsonObject = valueMap.toJsonObject();
    assertThat(jsonObject.get("foo")).isEqualTo(JSONObject.NULL);
  }

  @Test
  public void getInt() {
    assertThat(valueMap.getInt("a missing key", 1)).isEqualTo(1);

    valueMap.putValue("a number", 3.14);
    assertThat(valueMap.getInt("a number", 0)).isEqualTo(3);

    valueMap.putValue("a string number", "892");
    assertThat(valueMap.getInt("a string number", 0)).isEqualTo(892);

    valueMap.putValue("a string", "not really an int");
    assertThat(valueMap.getInt("a string", 0)).isEqualTo(0);
  }

  @Test
  public void getLong() {
    assertThat(valueMap.getLong("a missing key", 2)).isEqualTo(2);

    valueMap.putValue("a number", 3.14);
    assertThat(valueMap.getLong("a number", 0)).isEqualTo(3);

    valueMap.putValue("a string number", "88");
    assertThat(valueMap.getLong("a string number", 0)).isEqualTo(88);

    valueMap.putValue("a string", "not really a long");
    assertThat(valueMap.getLong("a string", 0)).isEqualTo(0);
  }

  @Test
  public void getFloat() {
    assertThat(valueMap.getFloat("foo", 0)).isEqualTo(0);

    valueMap.putValue("foo", 3.14);
    assertThat(valueMap.getFloat("foo", 0)).isEqualTo(3.14f);
  }

  @Test
  public void getDouble() {
    assertThat(valueMap.getDouble("a missing key", 3)).isEqualTo(3);

    valueMap.putValue("a number", Math.PI);
    assertThat(valueMap.getDouble("a number", Math.PI)).isEqualTo(Math.PI);

    valueMap.putValue("a string number", "3.14");
    assertThat(valueMap.getDouble("a string number", 0)).isEqualTo(3.14);

    valueMap.putValue("a string", "not really a double");
    assertThat(valueMap.getDouble("a string", 0)).isEqualTo(0);
  }

  @Test
  public void getChar() {
    assertThat(valueMap.getChar("a missing key", 'a')).isEqualTo('a');

    valueMap.putValue("a string", "f");
    assertThat(valueMap.getChar("a string", 'a')).isEqualTo('f');

    valueMap.putValue("a char", 'b');
    assertThat(valueMap.getChar("a char", 'a')).isEqualTo('b');
  }

  enum Soda {
    PEPSI,
    COKE
  }

  @Test
  public void getEnum() {
    try {
      valueMap.getEnum(null, "foo");
      fail("should have thrown IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("enumType may not be null");
    }

    assertThat(valueMap.getEnum(Soda.class, "a missing key")).isNull();

    valueMap.putValue("pepsi", Soda.PEPSI);
    assertThat(valueMap.getEnum(Soda.class, "pepsi")).isEqualTo(Soda.PEPSI);

    valueMap.putValue("coke", "COKE");
    assertThat(valueMap.getEnum(Soda.class, "coke")).isEqualTo(Soda.COKE);
  }

  @Test
  public void getValueMapWithClass() {
    valueMap.put("foo", "not a map");
    assertThat(valueMap.getValueMap("foo", Properties.class)).isNull();
  }

  @Test
  public void getList() {
    valueMap.put("foo", "not a list");
    assertThat(valueMap.getList("foo", Properties.class)).isNull();
  }

  @Test
  public void toStringMap() {
    assertThat(valueMap.toStringMap()).isEmpty();

    valueMap.put("foo", "bar");
    assertThat(valueMap.toStringMap()) //
        .isEqualTo(new ImmutableMap.Builder<String, Object>().put("foo", "bar").build());
  }

  private enum MyEnum {
    VALUE1,
    VALUE2
  }
}
