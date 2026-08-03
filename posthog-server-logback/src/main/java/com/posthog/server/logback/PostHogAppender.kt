package com.posthog.server.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import com.posthog.internal.errortracking.PostHogCapturedThrowables
import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface

/**
 * A Logback [AppenderBase] that reports logged errors to PostHog Error Tracking through the server
 * SDK.
 *
 * ## Wiring the client
 *
 * The appender needs a server [PostHogInterface]. Register one you already configure elsewhere:
 *
 * ```kotlin
 * val posthog = PostHog.with(PostHogConfig.builder("<API_KEY>").build())
 * PostHogAppender.setPostHog(posthog)
 * ```
 *
 * Registration is process-wide (Logback owns appender instances), so do it once during startup,
 * before the logging that should be captured. **Events logged before a client is registered are
 * dropped** — the appender has nowhere to send them.
 *
 * Alternatively, the appender can create and own its own client from `logback.xml` when you set
 * [apiKey] (and optionally [host]); that client is closed again on [stop]. An explicitly registered
 * client via [setPostHog] always wins over self-configuration: appenders never replace it. Each
 * self-configuring appender owns its own client, so a Logback configuration reload (new appender
 * started before the old one stops) keeps capturing.
 *
 * ```xml
 * <appender name="POSTHOG" class="com.posthog.server.logback.PostHogAppender">
 *     <apiKey>${POSTHOG_API_KEY}</apiKey>
 *     <minimumCaptureLevel>ERROR</minimumCaptureLevel>
 * </appender>
 * ```
 *
 * ## What is captured
 *
 * Only events at or above [minimumCaptureLevel] (default [Level.ERROR]) that carry a [Throwable]
 * are captured; events without a throwable are skipped (there is no message-only synthesis).
 * Events from the PostHog SDK's own loggers (`com.posthog` and `com.posthog.*`) are always skipped
 * so the SDK cannot recursively report its own errors.
 *
 * The throwable is sent through the server SDK's `captureException`, so request-context distinct-id
 * resolution and in-app frame configuration apply automatically — the appender runs on the logging
 * thread, inside any active `PostHogRequestContext` scope.
 *
 * Docs https://posthog.com/docs/error-tracking
 */
public class PostHogAppender : AppenderBase<ILoggingEvent>() {
    /**
     * Minimum log level captured. Defaults to `ERROR`. Set from `logback.xml` via
     * `<minimumCaptureLevel>WARN</minimumCaptureLevel>`.
     */
    @Volatile
    public var minimumCaptureLevel: Level = Level.ERROR

    /**
     * Optional PostHog project API key. When set and no client has been registered via
     * [setPostHog], the appender creates and owns its own server client on [start].
     */
    public var apiKey: String? = null

    /**
     * Optional PostHog host for the self-configured client. Ignored unless [apiKey] is set.
     */
    public var host: String? = null

    // Client this appender created from apiKey/host and is responsible for closing on stop().
    private var ownedPostHog: PostHogInterface? = null

    override fun start() {
        // Self-configure unless the application registered a client itself. A client another
        // appender self-configured is NOT a reason to skip: on a Logback config reload the new
        // appender starts before the old one stops, and the old one takes its client down with it.
        if (!explicitlyRegistered && !apiKey.isNullOrBlank()) {
            try {
                val builder = PostHogConfig.builder(apiKey!!.trim())
                host?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.host(it) }
                val client = PostHog.with(builder.build())
                ownedPostHog = client
                publishSelfConfiguredIfAbsent(client)
            } catch (e: Throwable) {
                addError("PostHogAppender failed to create a PostHog client from configuration.", e)
            }
        }
        super.start()
    }

    override fun stop() {
        super.stop()
        ownedPostHog?.let { owned ->
            try {
                // Captures are enqueued asynchronously and close() does not drain the queue, so ask
                // for a flush first. Both are best-effort: the flush runs on the queue executor that
                // close() then stops, so events logged right at shutdown may still be lost.
                owned.flush()
                owned.close()
            } catch (e: Throwable) {
                addError("PostHogAppender failed to close its PostHog client.", e)
            }
            // Only clear the shared reference if it still points at the client we own.
            clearPostHogIf(owned)
            ownedPostHog = null
        }
    }

    override fun append(event: ILoggingEvent) {
        // Appenders must never throw into the logging pipeline.
        try {
            val postHog = resolveClient() ?: return

            // Recursion guard: the SDK logs its own errors; capturing them would loop. Match the
            // exact SDK logger or its package (dot-terminated) so unrelated packages that merely
            // share the string prefix (e.g. com.posthogger.*) are still captured.
            val loggerName = event.loggerName
            if (loggerName == POSTHOG_LOGGER_NAME || loggerName?.startsWith(POSTHOG_LOGGER_PACKAGE_PREFIX) == true) {
                return
            }

            if (!event.level.isGreaterOrEqual(minimumCaptureLevel)) {
                return
            }

            // v1 only captures real throwables; no message-only synthesis.
            val throwable = (event.throwableProxy as? ThrowableProxy)?.throwable ?: return

            // Dedup: skip instances already reported through any capture path (an earlier log of
            // the same throwable, or a crash the uncaught handler already recorded — the crash
            // side always captures and only marks, so the log mirror is the side that yields).
            if (!PostHogCapturedThrowables.markAndCheck(throwable)) {
                return
            }

            postHog.captureException(throwable, buildProperties(event, throwable))
        } catch (e: Throwable) {
            addError("PostHogAppender failed to capture a logging event.", e)
        }
    }

    /**
     * An application-registered client always wins; otherwise this appender uses the client it
     * configured itself, so two self-configured appenders never send each other's events to the
     * wrong project, and falls back to whatever is registered process-wide.
     */
    private fun resolveClient(): PostHogInterface? =
        if (explicitlyRegistered) {
            sharedPostHog
        } else {
            ownedPostHog ?: sharedPostHog
        }

    private fun buildProperties(
        event: ILoggingEvent,
        throwable: Throwable,
    ): Map<String, Any> {
        val properties = mutableMapOf<String, Any>()
        properties[EXCEPTION_LEVEL] = mapLevel(event.level)
        event.loggerName?.let { properties[LOGGER_NAME] = it }

        // Attach the log message only when it adds information beyond the throwable's own message.
        val message = event.formattedMessage
        if (!message.isNullOrEmpty() && message != throwable.message) {
            properties[LOG_MESSAGE] = message
        }
        return properties
    }

    private fun mapLevel(level: Level): String =
        when {
            level.isGreaterOrEqual(Level.ERROR) -> LEVEL_ERROR
            else -> LEVEL_WARNING
        }

    public companion object {
        private const val POSTHOG_LOGGER_NAME = "com.posthog"
        private const val POSTHOG_LOGGER_PACKAGE_PREFIX = "com.posthog."

        private const val EXCEPTION_LEVEL = "\$exception_level"
        private const val LOGGER_NAME = "logger_name"
        private const val LOG_MESSAGE = "log_message"

        private const val LEVEL_ERROR = "error"
        private const val LEVEL_WARNING = "warning"

        @Volatile
        private var sharedPostHog: PostHogInterface? = null

        // True while the client came from setPostHog (the application owns it), false while it came
        // from an appender's own apiKey configuration. An application-registered client always wins:
        // appenders never replace it, and it outlives any appender.
        @Volatile
        private var explicitlyRegistered = false

        /**
         * Registers the PostHog server client the appender captures through. Call once during
         * application startup, before the logging that should be captured.
         *
         * @param postHog the configured server SDK client.
         */
        @JvmStatic
        @Synchronized
        public fun setPostHog(postHog: PostHogInterface) {
            sharedPostHog = postHog
            explicitlyRegistered = true
        }

        /**
         * Clears the registered client. Mostly useful for tests and clean shutdown.
         */
        @JvmStatic
        @Synchronized
        public fun clearPostHog() {
            sharedPostHog = null
            explicitlyRegistered = false
        }

        // Publishes a self-configured client as the process-wide fallback, but never over an
        // application-registered one — including a setPostHog that landed while the client was being
        // built. The appender keeps using its own client either way.
        @Synchronized
        private fun publishSelfConfiguredIfAbsent(postHog: PostHogInterface) {
            if (explicitlyRegistered) {
                return
            }
            sharedPostHog = postHog
        }

        // Clears the shared client only if it is the given instance, so a self-owned client's stop()
        // does not wipe a client someone else registered afterwards.
        @Synchronized
        private fun clearPostHogIf(expected: PostHogInterface) {
            if (sharedPostHog === expected) {
                sharedPostHog = null
            }
        }
    }
}
