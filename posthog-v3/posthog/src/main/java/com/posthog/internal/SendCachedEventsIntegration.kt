package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import java.io.File
import java.util.concurrent.Executors

internal class SendCachedEventsIntegration(private val config: PostHogConfig, private val api: PostHogApi, private val serializer: PostHogSerializer) : PostHogIntegration {
    override fun install() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogSendCachedEventsThread"))
        executor.execute {
            flushLegacyEvents()
            flushEvents()
        }
        executor.shutdown()
    }

    private fun flushLegacyEvents() {
        config.legacyStoragePrefix?.let {
            val legacyDir = File(it)
            val legacyFile = File(legacyDir, "${config.apiKey}.tmp")

            if (legacyFile.exists()) {
                val legacy = PostHogQueueFile.Builder(legacyFile)
                    .forceLegacy(true)
                    .build()

                val iterator = legacy.iterator()

                val events = mutableListOf<PostHogEvent>()

                while (iterator.hasNext()) {
                    val eventBytes = iterator.next()

                    val event = serializer.deserializeEvent(eventBytes.inputStream().reader().buffered())
                    event?.let {
                        events.add(event)
                    }
                }

                if (events.isNotEmpty()) {
                    api.batch(events)
                }

                legacyFile.delete()
            }
        }
    }

    private fun flushEvents() {
        config.storagePrefix?.let {
            val dir = File(it, config.apiKey)

            if (dir.exists()) {
                // TODO: in case this is executed after new events come in, we have to filter those
                // they are in the queue already
                val listFiles = dir.listFiles() ?: arrayOf()
                val events = mutableListOf<PostHogEvent>()
                val iterator = listFiles.iterator()

                while (iterator.hasNext()) {
                    val eventBytes = iterator.next()

                    val event = serializer.deserializeEvent(eventBytes.inputStream().reader().buffered())
                    event?.let {
                        events.add(event)
                    }
                }

                if (events.isNotEmpty()) {
                    api.batch(events)

                    listFiles.forEach { file ->
                        file.delete()
                    }
                }
            }
        }
    }
}
