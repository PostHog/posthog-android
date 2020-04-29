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

import android.app.Activity;
import android.os.Bundle;
import com.posthog.payloads.AliasPayload;
import com.posthog.payloads.IdentifyPayload;
import com.posthog.payloads.ScreenPayload;
import com.posthog.payloads.CapturePayload;

/** Abstraction for a task that a {@link Integration <?>} can execute. */
abstract class IntegrationOperation {

  static IntegrationOperation onActivityCreated(final Activity activity, final Bundle bundle) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityCreated(activity, bundle);
      }

      @Override
      public String toString() {
        return "Activity Created";
      }
    };
  }

  static IntegrationOperation onActivityStarted(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityStarted(activity);
      }

      @Override
      public String toString() {
        return "Activity Started";
      }
    };
  }

  static IntegrationOperation onActivityResumed(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityResumed(activity);
      }

      @Override
      public String toString() {
        return "Activity Resumed";
      }
    };
  }

  static IntegrationOperation onActivityPaused(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityPaused(activity);
      }

      @Override
      public String toString() {
        return "Activity Paused";
      }
    };
  }

  static IntegrationOperation onActivityStopped(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityStopped(activity);
      }

      @Override
      public String toString() {
        return "Activity Stopped";
      }
    };
  }

  static IntegrationOperation onActivitySaveInstanceState(
      final Activity activity, final Bundle bundle) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivitySaveInstanceState(activity, bundle);
      }

      @Override
      public String toString() {
        return "Activity Save Instance";
      }
    };
  }

  static IntegrationOperation onActivityDestroyed(final Activity activity) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.onActivityDestroyed(activity);
      }

      @Override
      public String toString() {
        return "Activity Destroyed";
      }
    };
  }

  static IntegrationOperation identify(final IdentifyPayload identifyPayload) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.identify(identifyPayload);
      }

      @Override
      public String toString() {
        return identifyPayload.toString();
      }
    };
  }

  static IntegrationOperation capture(final CapturePayload capturePayload) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.capture(capturePayload);
      }

      @Override
      public String toString() {
        return capturePayload.toString();
      }
    };
  }

  static IntegrationOperation screen(final ScreenPayload screenPayload) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.screen(screenPayload);
      }

      @Override
      public String toString() {
        return screenPayload.toString();
      }
    };
  }

  static IntegrationOperation alias(final AliasPayload aliasPayload) {
    return new IntegrationOperation() {
      @Override
      public void run(Integration<?> integration) {
        integration.alias(aliasPayload);
      }

      @Override
      public String toString() {
        return aliasPayload.toString();
      }
    };
  }

  static final IntegrationOperation FLUSH =
      new IntegrationOperation() {
        @Override
        void run(Integration<?> integration) {
          integration.flush();
        }

        @Override
        public String toString() {
          return "Flush";
        }
      };

  static final IntegrationOperation RESET =
      new IntegrationOperation() {
        @Override
        void run(Integration<?> integration) {
          integration.reset();
        }

        @Override
        public String toString() {
          return "Reset";
        }
      };

  private IntegrationOperation() {}

  /** Run this operation on the given integration. */
  abstract void run(Integration<?> integration);
}
