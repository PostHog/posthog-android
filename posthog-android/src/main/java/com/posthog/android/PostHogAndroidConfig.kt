package com.posthog.android

import com.posthog.PostHogConfig
import com.posthog.android.replay.PostHogReplayQueue
import com.posthog.android.replay.PostHogSessionReplayConfig
import com.posthog.internal.EndpointSpec
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogQueue

/**
 * Android SDK configuration.
 *
 * @param apiKey PostHog project API key. Leading and trailing whitespace is trimmed.
 * @param host PostHog ingestion host. Defaults to [DEFAULT_HOST].
 * @property captureApplicationLifecycleEvents Whether to capture application lifecycle events
 *   automatically, including app installed, app updated, app opened, and app backgrounded.
 * @property captureDeepLinks Whether to capture `Deep Link Opened` events automatically.
 * @property captureScreenViews Whether to capture a `$screen` event whenever a foreground
 *   Activity starts (via `ActivityLifecycleCallbacks.onActivityStarted`). When enabled, the most
 *   recent screen name is also attached as `$screen_name` to every subsequent event captured by
 *   the SDK. To opt out of `$screen_name` stamping entirely, set this to `false` and avoid calling
 *   `PostHog.screen(...)` manually. Default: `true`.
 * @property sessionReplayConfig Android-specific session replay capture options.
 * @property capturePushNotificationSubscriptions Whether to auto-register this device's push
 *   token at startup. When enabled and Firebase Messaging is on the classpath, the SDK fetches the
 *   FCM token + Firebase `project_id` and registers them so PostHog Workflows can deliver push
 *   notifications. No effect when Firebase is absent (a single debug log). Instant token refresh
 *   still needs the app to forward `onNewToken` to `registerPushNotificationToken(...)`. Default: `true`.
 * @property capturePushNotificationOpened Whether to auto-capture `$push_notification_opened` for
 *   cold-start taps on a tray notification (detected via the launch intent's `google.message_id`
 *   extra). Foreground data messages and warm-start `onNewIntent` are not observable here — forward
 *   those to `capturePushNotificationOpened(...)` manually. Default: `true`.
 */
public open class PostHogAndroidConfig
    @JvmOverloads
    constructor(
        apiKey: String,
        host: String = DEFAULT_HOST,
        public var captureApplicationLifecycleEvents: Boolean = true,
        public var captureDeepLinks: Boolean = true,
        public var captureScreenViews: Boolean = true,
        public var sessionReplayConfig: PostHogSessionReplayConfig = PostHogSessionReplayConfig(),
        // appended after sessionReplayConfig so @JvmOverloads keeps the pre-existing overloads
        public var capturePushNotificationSubscriptions: Boolean = true,
        public var capturePushNotificationOpened: Boolean = true,
    ) : PostHogConfig(
            apiKey = apiKey,
            host = host,
            queueProvider = { config, api, endpoint, storagePrefix, executor ->
                val spec =
                    when (endpoint) {
                        PostHogApiEndpoint.BATCH -> EndpointSpec.batch(config, api, storagePrefix)
                        PostHogApiEndpoint.SNAPSHOT -> EndpointSpec.snapshot(config, api, storagePrefix)
                    }
                val defaultQueue = PostHogQueue(config, spec, executor)
                if (endpoint == PostHogApiEndpoint.SNAPSHOT) {
                    val replayQueue = PostHogReplayQueue(config, defaultQueue, storagePrefix, executor)
                    (config as? PostHogAndroidConfig)?.replayQueueHolder = replayQueue
                    replayQueue
                } else {
                    defaultQueue
                }
            },
        ) {
        internal var replayQueueHolder: PostHogReplayQueue? = null
    }
