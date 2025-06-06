package com.posthog.android.replay.internal

import android.annotation.SuppressLint
import android.os.Build
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.interruptSafely
import com.posthog.internal.replay.RRPluginEvent
import com.posthog.internal.replay.capture
import java.text.SimpleDateFormat
import java.util.Locale

internal class PostHogLogCatIntegration(private val config: PostHogAndroidConfig) : PostHogIntegration {
    @Volatile
    private var logcatInProgress = false

    private var logcatThread: Thread? = null

    private val isSessionReplayActive: Boolean
        get() = postHog?.isSessionReplayActive() ?: false

    private var postHog: PostHogInterface? = null

    private companion object {
        @Volatile
        private var integrationInstalled = false
    }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled || !config.sessionReplayConfig.captureLogcat || !isSupported()) {
            return
        }
        integrationInstalled = true
        this.postHog = postHog
        val cmd = mutableListOf("logcat", "-v", "threadtime", "*:E")
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss.mmm", Locale.ROOT)
        cmd.add("-T")
        cmd.add(sdf.format(config.dateProvider.currentTimeMillis()))

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
                                    val log = LogcatParser().parse(line) ?: continue

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

    @SuppressLint("AnnotateVersionCheck")
    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    override fun uninstall() {
        integrationInstalled = false
        this.postHog = null
        logcatInProgress = false
        logcatThread?.interruptSafely()
    }
}
