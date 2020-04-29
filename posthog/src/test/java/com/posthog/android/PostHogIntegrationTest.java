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

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static com.posthog.android.PostHog.LogLevel.NONE;
import static com.posthog.android.PostHogIntegration.MAX_QUEUE_SIZE;
import static com.posthog.android.TestUtils.SynchronousExecutor;
import static com.posthog.android.TestUtils.CAPTURE_PAYLOAD;
import static com.posthog.android.TestUtils.CAPTURE_PAYLOAD_JSON;
import static com.posthog.android.TestUtils.mockApplication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.posthog.android.Client.Connection;
import com.posthog.android.PayloadQueue.PersistentQueue;
import com.posthog.android.payloads.CapturePayload;
import com.posthog.android.internal.Utils;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PostHogIntegrationTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();
  QueueFile queueFile;

  private static Client.Connection mockConnection() {
    return mockConnection(mock(HttpURLConnection.class));
  }

  private static Client.Connection mockConnection(HttpURLConnection connection) {
    return new Client.Connection(connection, mock(InputStream.class), mock(OutputStream.class)) {
      @Override
      public void close() throws IOException {
        super.close();
      }
    };
  }

  @Before
  public void setUp() throws IOException {
    queueFile = new QueueFile(new File(folder.getRoot(), "queue-file"));
  }

  @After
  public void tearDown() {
    assertThat(ShadowLog.getLogs()).isEmpty();
  }

  @Test
  public void enqueueAddsToQueueFile() throws IOException {
    PayloadQueue payloadQueue = new PersistentQueue(queueFile);
    PostHogIntegration posthogIntegration = new PostHogBuilder().payloadQueue(payloadQueue).build();
    posthogIntegration.performEnqueue(CAPTURE_PAYLOAD);
    assertThat(payloadQueue.size()).isEqualTo(1);
  }

  @Test
  public void enqueueLimitsQueueSize() throws IOException {
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    // We want to trigger a remove, but not a flush.
    when(payloadQueue.size()).thenReturn(0, MAX_QUEUE_SIZE, MAX_QUEUE_SIZE, 0);
    PostHogIntegration posthogIntegration = new PostHogBuilder().payloadQueue(payloadQueue).build();

    posthogIntegration.performEnqueue(CAPTURE_PAYLOAD);

    verify(payloadQueue).remove(1); // Oldest entry is removed.
    verify(payloadQueue).add(any(byte[].class)); // Newest entry is added.
  }

  @Test
  public void exceptionIgnoredIfFailedToRemove() throws IOException {
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    doThrow(new IOException("no remove for you.")).when(payloadQueue).remove(1);
    when(payloadQueue.size()).thenReturn(MAX_QUEUE_SIZE); // trigger a remove
    PostHogIntegration posthogIntegration = new PostHogBuilder().payloadQueue(payloadQueue).build();

    try {
      posthogIntegration.performEnqueue(CAPTURE_PAYLOAD);
    } catch (IOError unexpected) {
      fail("did not expect QueueFile to throw an error.");
    }

    verify(payloadQueue, never()).add(any(byte[].class));
  }

  @Test
  public void enqueueMaxTriggersFlush() throws IOException {
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    Client client = mock(Client.class);
    Client.Connection connection = mockConnection();
    when(client.batch()).thenReturn(connection);

    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client)
            .flushSize(5)
            .payloadQueue(payloadQueue)
            .build();

    for (int i = 0; i < 4; i++) {
      posthogIntegration.performEnqueue(CAPTURE_PAYLOAD);
    }
    verifyZeroInteractions(client);
    // Only the last enqueue should trigger an batch.
    posthogIntegration.performEnqueue(CAPTURE_PAYLOAD);

    verify(client).batch();
  }

  @Test
  public void flushRemovesItemsFromQueue() throws IOException {
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    Client client = mock(Client.class);
    when(client.batch()).thenReturn(mockConnection());
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client)
            .payloadQueue(payloadQueue)
            .build();
    byte[] bytes = CAPTURE_PAYLOAD_JSON.getBytes();
    for (int i = 0; i < 4; i++) {
      queueFile.add(bytes);
    }

    posthogIntegration.submitFlush();

    assertThat(queueFile.size()).isEqualTo(0);
  }

  @Test
  public void flushSubmitsToExecutor() throws IOException {
    ExecutorService executor = spy(new SynchronousExecutor());
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    when(payloadQueue.size()).thenReturn(1);
    PostHogIntegration dispatcher =
        new PostHogBuilder() //
            .payloadQueue(payloadQueue)
            .networkExecutor(executor)
            .build();

    dispatcher.submitFlush();

    verify(executor).submit(any(Runnable.class));
  }

  @Test
  public void flushChecksIfExecutorIsShutdownFirst() {
    ExecutorService executor = spy(new SynchronousExecutor());
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    when(payloadQueue.size()).thenReturn(1);
    PostHogIntegration dispatcher =
        new PostHogBuilder() //
            .payloadQueue(payloadQueue)
            .networkExecutor(executor)
            .build();

    dispatcher.shutdown();
    executor.shutdown();
    dispatcher.submitFlush();

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void flushWhenDisconnectedSkipsUpload() throws IOException {
    NetworkInfo networkInfo = mock(NetworkInfo.class);
    when(networkInfo.isConnectedOrConnecting()).thenReturn(false);
    ConnectivityManager connectivityManager = mock(ConnectivityManager.class);
    when(connectivityManager.getActiveNetworkInfo()).thenReturn(networkInfo);
    Context context = mockApplication();
    when(context.getSystemService(CONNECTIVITY_SERVICE)).thenReturn(connectivityManager);
    Client client = mock(Client.class);
    PostHogIntegration posthogIntegration =
        new PostHogBuilder().context(context).client(client).build();

    posthogIntegration.submitFlush();

    verify(client, never()).batch();
  }

  @Test
  public void flushWhenQueueSizeIsLessThanOneSkipsUpload() throws IOException {
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    when(payloadQueue.size()).thenReturn(0);
    Context context = mockApplication();
    Client client = mock(Client.class);
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .payloadQueue(payloadQueue)
            .context(context)
            .client(client)
            .build();

    posthogIntegration.submitFlush();

    verifyZeroInteractions(context);
    verify(client, never()).batch();
  }

  @Test
  public void flushDisconnectsConnection() throws IOException {
    Client client = mock(Client.class);
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    queueFile.add(CAPTURE_PAYLOAD_JSON.getBytes());
    HttpURLConnection urlConnection = mock(HttpURLConnection.class);
    Client.Connection connection = mockConnection(urlConnection);
    when(client.batch()).thenReturn(connection);
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client) //
            .payloadQueue(payloadQueue) //
            .build();

    posthogIntegration.submitFlush();

    verify(urlConnection, times(2)).disconnect();
  }

  @Test
  public void removesRejectedPayloads() throws IOException {
    // todo: rewrite using mockwebserver.
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    Client client = mock(Client.class);
    when(client.batch())
        .thenReturn(
            new Connection(
                mock(HttpURLConnection.class), mock(InputStream.class), mock(OutputStream.class)) {
              @Override
              public void close() throws IOException {
                throw new Client.HTTPException(400, "Bad Request", "bad request");
              }
            });
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client)
            .payloadQueue(payloadQueue)
            .build();
    for (int i = 0; i < 4; i++) {
      payloadQueue.add(CAPTURE_PAYLOAD_JSON.getBytes());
    }

    posthogIntegration.submitFlush();

    assertThat(queueFile.size()).isEqualTo(0);
    verify(client).batch();
  }

  @Test
  public void ignoresServerError() throws IOException {
    // todo: rewrite using mockwebserver.
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    Client client = mock(Client.class);
    when(client.batch())
        .thenReturn(
            new Connection(
                mock(HttpURLConnection.class), mock(InputStream.class), mock(OutputStream.class)) {
              @Override
              public void close() throws IOException {
                throw new Client.HTTPException(
                    500, "Internal Server Error", "internal server error");
              }
            });
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client)
            .payloadQueue(payloadQueue)
            .build();
    for (int i = 0; i < 4; i++) {
      payloadQueue.add(CAPTURE_PAYLOAD_JSON.getBytes());
    }

    posthogIntegration.submitFlush();

    assertThat(queueFile.size()).isEqualTo(4);
    verify(client).batch();
  }

  @Test
  public void ignoresHTTP429Error() throws IOException {
    // todo: rewrite using mockwebserver.
    PayloadQueue payloadQueue = new PayloadQueue.PersistentQueue(queueFile);
    Client client = mock(Client.class);
    when(client.batch())
        .thenReturn(
            new Connection(
                mock(HttpURLConnection.class), mock(InputStream.class), mock(OutputStream.class)) {
              @Override
              public void close() throws IOException {
                throw new Client.HTTPException(429, "Too Many Requests", "too many requests");
              }
            });
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .client(client)
            .payloadQueue(payloadQueue)
            .build();
    for (int i = 0; i < 4; i++) {
      payloadQueue.add(CAPTURE_PAYLOAD_JSON.getBytes());
    }

    posthogIntegration.submitFlush();

    // Verify that messages were not removed from the queue when server returned a 429.
    assertThat(queueFile.size()).isEqualTo(4);
    verify(client).batch();
  }

  @Test
  public void serializationErrorSkipsAddingPayload() throws IOException {
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    Cartographer cartographer = mock(Cartographer.class);
    CapturePayload payload = new CapturePayload.Builder().event("event").distinctId("distinctId").build();
    PostHogIntegration posthogIntegration =
        new PostHogBuilder() //
            .cartographer(cartographer)
            .payloadQueue(payloadQueue)
            .build();

    // Serialized json is null.
    when(cartographer.toJson(anyMap())).thenReturn(null);
    posthogIntegration.performEnqueue(payload);
    verify(payloadQueue, never()).add((byte[]) any());

    // Serialized json is empty.
    when(cartographer.toJson(anyMap())).thenReturn("");
    posthogIntegration.performEnqueue(payload);
    verify(payloadQueue, never()).add((byte[]) any());

    // Serialized json is too large (> MAX_PAYLOAD_SIZE).
    StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < PostHogIntegration.MAX_PAYLOAD_SIZE + 1; i++) {
      stringBuilder.append("a");
    }
    when(cartographer.toJson(anyMap())).thenReturn(stringBuilder.toString());
    posthogIntegration.performEnqueue(payload);
    verify(payloadQueue, never()).add((byte[]) any());
  }

  @Test
  public void shutdown() throws IOException {
    PayloadQueue payloadQueue = mock(PayloadQueue.class);
    PostHogIntegration posthogIntegration = new PostHogBuilder().payloadQueue(payloadQueue).build();

    posthogIntegration.shutdown();

    verify(payloadQueue).close();
  }

  @Test
  public void payloadVisitorReadsOnly475KB() throws IOException {
    PostHogIntegration.PayloadWriter payloadWriter =
        new PostHogIntegration.PayloadWriter(
            mock(PostHogIntegration.BatchPayloadWriter.class), Crypto.none());
    byte[] bytes =
        ("{\n"
                + "        \"context\": {\n"
                + "          \"library\": \"posthog-android\",\n"
                + "          \"libraryVersion\": \"0.4.4\",\n"
                + "          \"telephony\": {\n"
                + "            \"radio\": \"gsm\",\n"
                + "            \"carrier\": \"FI elisa\"\n"
                + "          },\n"
                + "          \"wifi\": {\n"
                + "            \"connected\": false,\n"
                + "            \"available\": false\n"
                + "          },\n"
                + "          \"providers\": {\n"
                + "            \"Tapstream\": false,\n"
                + "            \"Amplitude\": false,\n"
                + "            \"Localytics\": false,\n"
                + "            \"Flurry\": false,\n"
                + "            \"Countly\": false,\n"
                + "            \"Bugsnag\": false,\n"
                + "            \"Quantcast\": false,\n"
                + "            \"Crittercism\": false,\n"
                + "            \"Google PostHog\": false,\n"
                + "            \"Omniture\": false,\n"
                + "            \"Mixpanel\": false\n"
                + "          },\n"
                + "          \"location\": {\n"
                + "            \"speed\": 0,\n"
                + "            \"longitude\": 24.937207,\n"
                + "            \"latitude\": 60.2495497\n"
                + "          },\n"
                + "          \"locale\": {\n"
                + "            \"carrier\": \"FI elisa\",\n"
                + "            \"language\": \"English\",\n"
                + "            \"country\": \"United States\"\n"
                + "          },\n"
                + "          \"device\": {\n"
                + "            \"distinctId\": \"123\",\n"
                + "            \"brand\": \"samsung\",\n"
                + "            \"release\": \"4.2.2\",\n"
                + "            \"manufacturer\": \"samsung\",\n"
                + "            \"sdk\": 17\n"
                + "          },\n"
                + "          \"display\": {\n"
                + "            \"density\": 1.5,\n"
                + "            \"width\": 800,\n"
                + "            \"height\": 480\n"
                + "          },\n"
                + "          \"build\": {\n"
                + "            \"name\": \"1.0\",\n"
                + "            \"code\": 1\n"
                + "          },\n"
                + "          \"ip\": \"80.186.195.102\",\n"
                + "          \"inferredIp\": true\n"
                + "        }\n"
                + "      }")
            .getBytes(); // length 1432
    // Fill the payload with (1432 * 500) = ~716kb of data
    for (int i = 0; i < 500; i++) {
      queueFile.add(bytes);
    }

    queueFile.forEach(payloadWriter);

    // Verify only (331 * 1432) = 473992 < 475KB bytes are read
    assertThat(payloadWriter.payloadCount).isEqualTo(331);
  }

  private static class PostHogBuilder {

    Client client;
    Stats stats;
    PayloadQueue payloadQueue;
    Context context;
    Cartographer cartographer;
    int flushInterval = Utils.DEFAULT_FLUSH_INTERVAL;
    int flushSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    Logger logger = Logger.with(NONE);
    ExecutorService networkExecutor;

    PostHogBuilder() {
      initMocks(this);
      context = mockApplication();
      when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)) //
          .thenReturn(PERMISSION_DENIED);
      cartographer = Cartographer.INSTANCE;
    }

    public PostHogBuilder client(Client client) {
      this.client = client;
      return this;
    }

    public PostHogBuilder stats(Stats stats) {
      this.stats = stats;
      return this;
    }

    public PostHogBuilder payloadQueue(PayloadQueue payloadQueue) {
      this.payloadQueue = payloadQueue;
      return this;
    }

    public PostHogBuilder context(Context context) {
      this.context = context;
      return this;
    }

    public PostHogBuilder cartographer(Cartographer cartographer) {
      this.cartographer = cartographer;
      return this;
    }

    public PostHogBuilder flushInterval(int flushInterval) {
      this.flushInterval = flushInterval;
      return this;
    }

    public PostHogBuilder flushSize(int flushSize) {
      this.flushSize = flushSize;
      return this;
    }

    public PostHogBuilder log(Logger logger) {
      this.logger = logger;
      return this;
    }

    public PostHogBuilder networkExecutor(ExecutorService networkExecutor) {
      this.networkExecutor = networkExecutor;
      return this;
    }

    PostHogIntegration build() {
      if (context == null) {
        context = mockApplication();
        when(context.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE)) //
            .thenReturn(PERMISSION_DENIED);
      }
      if (client == null) {
        client = mock(Client.class);
      }
      if (cartographer == null) {
        cartographer = Cartographer.INSTANCE;
      }
      if (payloadQueue == null) {
        payloadQueue = mock(PayloadQueue.class);
      }
      if (stats == null) {
        stats = mock(Stats.class);
      }
      if (networkExecutor == null) {
        networkExecutor = new SynchronousExecutor();
      }
      return new PostHogIntegration(
          context,
          client,
          cartographer,
          networkExecutor,
          payloadQueue,
          stats,
          flushInterval,
          flushSize,
          logger,
          Crypto.none());
    }
  }
}
