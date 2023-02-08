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

import static com.posthog.android.TestUtils.grantPermission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.MockitoAnnotations.initMocks;

import android.Manifest;
import com.google.common.util.concurrent.MoreExecutors;
import com.posthog.android.PostHog.Builder;
import com.posthog.android.payloads.BasePayload;
import com.posthog.android.payloads.ScreenPayload;
import com.posthog.android.payloads.CapturePayload;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MiddlewareTest {

  PostHog.Builder builder;

  @Before
  public void setUp() {
    initMocks(this);
    PostHog.INSTANCES.clear();
    grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);
    builder =
        new Builder(RuntimeEnvironment.application, "api_key")
            .executor(MoreExecutors.newDirectExecutorService());
  }

  @Test
  public void middlewareCanShortCircuit() throws Exception {
    final AtomicReference<CapturePayload> payloadRef = new AtomicReference<>();
    PostHog posthog =
        builder
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    payloadRef.set((CapturePayload) chain.payload());
                  }
                })
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    throw new AssertionError("should not be invoked");
                  }
                })
            .build();

    posthog.capture("foo");
//    assertThat(payloadRef.get().event()).isEqualTo("foo");
  }

  @Test
  public void middlewareCanProceed() throws Exception {
    final AtomicReference<ScreenPayload> payloadRef = new AtomicReference<>();
    PostHog posthog =
        builder
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    chain.proceed(chain.payload());
                  }
                })
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    BasePayload payload = chain.payload();
                    payloadRef.set((ScreenPayload) payload);
                    chain.proceed(payload);
                  }
                })
            .build();

    posthog.screen("foo");
//    assertThat(payloadRef.get().name()).isEqualTo("foo");
  }

  @Test
  public void middlewareCanTransform() throws Exception {
    final AtomicReference<BasePayload> payloadRef = new AtomicReference<>();
    PostHog posthog =
        builder
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    chain.proceed(chain.payload().toBuilder().messageId("override").build());
                  }
                })
            .middleware(
                new Middleware() {
                  @Override
                  public void intercept(Chain chain) {
                    BasePayload payload = chain.payload();
                    payloadRef.set(payload);
                    chain.proceed(payload);
                  }
                })
            .build();

    posthog.identify("prateek");
//    assertThat(payloadRef.get().messageId()).isEqualTo("override");
  }
}
