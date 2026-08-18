package com.posthog.android.replay.internal

import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.PostHogVisibleForTesting
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.interruptSafely
import com.posthog.internal.replay.RRPluginEvent
import com.posthog.internal.replay.capture
import java.util.concurrent.atomic.AtomicBoolean

internal class PostHogLogCatIntegration(private val config: PostHogAndroidConfig) : PostHogIntegration {
    @Volatile
    private var logcatInProgress = false

    private var logcatThread: Thread? = null

    private val isSessionReplayActive: Boolean
        get() = postHog?.isSessionReplayActive() ?: false

    private var postHog: PostHogInterface? = null
    private var ownsInstallation = false

    private companion object {
        private val integrationInstalled = AtomicBoolean(false)
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        this.postHog = postHog
        val captureLogcat = config.remoteConfigHolder?.isConsoleLogRecordingEnabled() ?: true
        if (!config.sessionReplayConfig.captureLogcat || !captureLogcat) {
            return
        }
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true
        val cmd = logcatCommand(config.dateProvider.currentTimeMillis())
        val logcatParser = LogcatParser()

        logcatInProgress = false
        logcatThread?.interruptSafely()
        logcatThread =
            Thread {
                var process: Process? = null
                try {
                    process = Runtime.getRuntime().exec(cmd.toTypedArray())
                    process.inputStream.bufferedReader().use {
                        var line: String? = null
                        logcatInProgress = true
                        do {
                            try {
                                line = it.readLine()

                                // do not capture console logs if session replay is disabled
                                if (!isSessionReplayActive) {
                                    continue
                                }

                                if (line.isNullOrEmpty()) {
                                    continue
                                }
                                // TODO: filter out all non useful stuff
                                if (line.contains("PostHog") || line.contains("StrictMode")) {
                                    continue
                                } else {
                                    val log = logcatParser.parse(line) ?: continue

                                    val props = mutableMapOf<String, Any>()
                                    props["level"] = log.level.toString()
                                    val tag = log.tag?.trim() ?: ""
                                    val content = log.text?.trim() ?: ""
                                    props["payload"] = listOf("$tag: $content")
                                    val time = log.time?.time?.time ?: config.dateProvider.currentTimeMillis()
                                    val event = RRPluginEvent("rrweb/console@1", props, time)
                                    // TODO: batch events
                                    listOf(event).capture(postHog)
                                }
                            } catch (e: Throwable) {
                                // ignore
                            }
                        } while (line != null && logcatInProgress)
                    }
                } catch (e: Throwable) {
                    // ignore
                } finally {
                    process?.destroy()
                }
            }
        logcatThread?.start()
    }

    override fun onRemoteConfig(loaded: Boolean) {
        // Only react to a live config; a failed attempt applies no fresh values.
        if (!loaded) {
            return
        }
        val captureLogcat = config.remoteConfigHolder?.isConsoleLogRecordingEnabled() ?: true
        if (captureLogcat) {
            postHog?.let { install(it) }
        } else {
            uninstall()
        }
    }

    @PostHogVisibleForTesting
    internal fun isInstalled(): Boolean = integrationInstalled.get()

    @PostHogVisibleForTesting
    internal fun logcatCommand(timestampMillis: Long): MutableList<String> =
        mutableListOf(
            "logcat",
            "-v",
            "epoch",
            "-T",
            "${timestampMillis / 1000}.${(timestampMillis % 1000).toString().padStart(3, '0')}",
            "*:E",
        )

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        try {
            logcatInProgress = false
            logcatThread?.interruptSafely()
        } finally {
            ownsInstallation = false
            integrationInstalled.set(false)
        }
    }
}
