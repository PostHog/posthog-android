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

import static junit.framework.Assert.fail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.Config.NONE;

import android.net.Uri;
import com.posthog.internal.Private;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = NONE)
public class ClientTest {

  @Rule public MockWebServer server = new MockWebServer();
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private Client client;
  private Client mockClient;
  @Private HttpURLConnection mockConnection;

  @Before
  public void setUp() {
    mockConnection = mock(HttpURLConnection.class);

    client =
        new Client(
            "foo",
            "https://testapp.posthog.com",
            new ConnectionFactory() {
              @Override
              protected HttpURLConnection openConnection(String url) throws IOException {
                String path = Uri.parse(url).getPath();
                assertThat(url).isEqualTo("https://testapp.posthog.com/batch");
                URL mockServerURL = server.getUrl(path);
                return super.openConnection(mockServerURL.toString());
              }
            });

    mockClient =
        new Client(
            "foo",
            "https://testapp.posthog.com",
            new ConnectionFactory() {
              @Override
              protected HttpURLConnection openConnection(String url) throws IOException {
                return mockConnection;
              }
            });
  }

  @Test
  public void batch() throws Exception {
    server.enqueue(new MockResponse());

    assertThat(client.host).isEqualTo("https://testapp.posthog.com");

    Client.Connection connection = client.batch();
    assertThat(connection.os).isNotNull();
    assertThat(connection.is).isNull();
    assertThat(connection.connection.getResponseCode()).isEqualTo(200); // consume the response.
    RecordedRequestAssert.assertThat(server.takeRequest())
        .hasRequestLine("POST /batch HTTP/1.1")
        .containsHeader("User-Agent", ConnectionFactory.USER_AGENT)
        .containsHeader("Content-Type", "application/json")
        .containsHeader("Content-Encoding", "gzip");
  }

  @Test
  public void closingUploadConnectionClosesStreams() throws Exception {
    OutputStream os = mock(OutputStream.class);
    when(mockConnection.getOutputStream()).thenReturn(os);
    when(mockConnection.getResponseCode()).thenReturn(200);

    Client.Connection connection = mockClient.batch();
    verify(mockConnection).setDoOutput(true);

    connection.close();
    verify(mockConnection).disconnect();
    verify(os).close();
  }

  @Test
  public void closingUploadConnectionClosesStreamsForNon200Response() throws Exception {
    OutputStream os = mock(OutputStream.class);
    when(mockConnection.getOutputStream()).thenReturn(os);
    when(mockConnection.getResponseCode()).thenReturn(202);

    Client.Connection connection = mockClient.batch();
    verify(mockConnection).setDoOutput(true);

    connection.close();
    verify(mockConnection).disconnect();
    verify(os).close();
  }

  @Test
  public void batchFailureClosesStreamsAndThrowsException() throws Exception {
    OutputStream os = mock(OutputStream.class);
    InputStream is = mock(InputStream.class);
    when(mockConnection.getOutputStream()).thenReturn(os);
    when(mockConnection.getResponseCode()).thenReturn(300);
    when(mockConnection.getResponseMessage()).thenReturn("bar");
    when(mockConnection.getInputStream()).thenReturn(is);

    Client.Connection connection = mockClient.batch();
    verify(mockConnection).setDoOutput(true);

    try {
      connection.close();
      fail(">= 300 return code should throw an exception");
    } catch (Client.HTTPException e) {
      assertThat(e)
          .hasMessage(
              "HTTP 300: bar. "
                  + "Response: Could not read response body for rejected message: "
                  + "java.io.IOException: Underlying input stream returned zero bytes");
    }
    verify(mockConnection).disconnect();
    verify(os).close();
  }

  @Test
  public void batchFailureWithErrorStreamClosesStreamsAndThrowsException() throws Exception {
    OutputStream os = mock(OutputStream.class);
    InputStream is = mock(InputStream.class);
    when(mockConnection.getOutputStream()).thenReturn(os);
    when(mockConnection.getResponseCode()).thenReturn(404);
    when(mockConnection.getResponseMessage()).thenReturn("bar");
    when(mockConnection.getInputStream()).thenThrow(new FileNotFoundException());
    when(mockConnection.getErrorStream()).thenReturn(is);

    Client.Connection connection = mockClient.batch();
    verify(mockConnection).setDoOutput(true);

    try {
      connection.close();
      fail(">= 300 return code should throw an exception");
    } catch (Client.HTTPException e) {
      assertThat(e)
          .hasMessage(
              "HTTP 404: bar. "
                  + "Response: Could not read response body for rejected message: "
                  + "java.io.IOException: Underlying input stream returned zero bytes");
    }
    verify(mockConnection).disconnect();
    verify(os).close();
  }

  static class RecordedRequestAssert
      extends AbstractAssert<RecordedRequestAssert, RecordedRequest> {

    static RecordedRequestAssert assertThat(RecordedRequest recordedRequest) {
      return new RecordedRequestAssert(recordedRequest);
    }

    protected RecordedRequestAssert(RecordedRequest actual) {
      super(actual, RecordedRequestAssert.class);
    }

    public RecordedRequestAssert containsHeader(String name, String expectedHeader) {
      isNotNull();
      String actualHeader = actual.getHeader(name);
      Assertions.assertThat(actualHeader)
          .overridingErrorMessage(
              "Expected header <%s> to be <%s> but was <%s>.", name, expectedHeader, actualHeader)
          .isEqualTo(expectedHeader);
      return this;
    }

    public RecordedRequestAssert containsHeader(String name) {
      isNotNull();
      String actualHeader = actual.getHeader(name);
      Assertions.assertThat(actualHeader)
          .overridingErrorMessage(
              "Expected header <%s> to not be empty but was.", name, actualHeader)
          .isNotNull()
          .isNotEmpty();
      return this;
    }

    public RecordedRequestAssert hasRequestLine(String requestLine) {
      isNotNull();
      String actualRequestLine = actual.getRequestLine();
      Assertions.assertThat(actualRequestLine)
          .overridingErrorMessage(
              "Expected requestLine <%s> to be <%s> but was not.", actualRequestLine, requestLine)
          .isEqualTo(requestLine);
      return this;
    }
  }
}
