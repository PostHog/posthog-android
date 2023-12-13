package com.posthog.android.replay.internal

import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.interruptSafely
import com.posthog.internal.replay.RRPluginEvent
import com.posthog.internal.replay.capture
import java.text.SimpleDateFormat
import java.util.Locale

internal class PostHogLogCatWatcher(private val config: PostHogAndroidConfig) {

    @Volatile
    private var logcatInProgress = false

    private var logcatThread: Thread? = null

    fun init() {
        if (!config.sessionReplayConfig.captureLogcat) {
            return
        }
        // TODO: check if its API 23 or higher
        val cmd = mutableListOf("logcat", "-v", "threadtime", "*:E")
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss.mmm", Locale.ROOT)
        cmd.add("-T")
        cmd.add(sdf.format(config.dateProvider.currentTimeMillis()))

        logcatInProgress = false
        logcatThread?.interruptSafely()
        logcatThread = Thread {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(cmd.toTypedArray())
                process.inputStream.bufferedReader().use {
                    var line: String? = null
                    logcatInProgress = true
                    do {
                        try {
                            line = it.readLine()

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
                                listOf(event).capture()
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

    fun stop() {
        logcatInProgress = false
        logcatThread?.interruptSafely()
    }
}
