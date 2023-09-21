package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.Date
import java.util.concurrent.Executors

internal class SendCachedEventsIntegration(private val config: PostHogConfig, private val api: PostHogApi, private val serializer: PostHogSerializer, private val startDate: Date) : PostHogIntegration {
    override fun install() {
        val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogSendCachedEventsThread"))
        executor.execute {
            if (config.networkStatus?.isConnected() != true) {
                config.logger.log("Network isn't connected.")
                return@execute
            }

            flushLegacyEvents()
            flushEvents()
        }
        executor.shutdown()
    }

    // TODO: respect maxBatchSize

    private fun flushLegacyEvents() {
        config.legacyStoragePrefix?.let {
            val legacyDir = File(it)
            val legacyFile = File(legacyDir, "${config.apiKey}.tmp")

            if (!legacyFile.exists()) {
                return
            }

            try {
                val legacy = PostHogQueueFile.Builder(legacyFile)
                    .forceLegacy(true)
                    .build()

                val iterator = legacy.iterator()

                val events = mutableListOf<PostHogEvent>()

                while (iterator.hasNext()) {
                    val eventBytes = iterator.next()

                    try {
                        val inputStream = config.encryption?.decrypt(eventBytes.inputStream()) ?: eventBytes.inputStream()
                        val event = serializer.deserializeEvent(inputStream.reader().buffered())
                        event?.let {
                            events.add(event)
                        }
                    } catch (e: Throwable) {
                        iterator.remove()
                        config.logger.log("Event failed to parse: $e.")
                    }
                }

                if (events.isNotEmpty()) {
                    var deleteFiles = true
                    try {
                        api.batch(events)
                    } catch (e: PostHogApiError) {
                        if (e.statusCode >= 400) {
                            // TODO: the reason to delete or not the files?
                        }
                        throw e
                    } catch (e: IOException) {
                        // no connection should try again
                        if (e.isNetworkingError()) {
                            deleteFiles = false
                        }
                        throw e
                    } finally {
                        if (deleteFiles) {
                            legacyFile.delete()
                        }
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Flushing legacy events failed: $e.")
            }
        }
    }

    private fun flushEvents() {
        config.storagePrefix?.let {
            val dir = File(it, config.apiKey)

            if (!dir.exists()) {
                return
            }

            // so that we don't try to send events in this batch that is already in the queue
            // but just cached events
            val time = startDate.time
            val fileFilter = FileFilter { file -> file.lastModified() <= time }

            val listFiles = (dir.listFiles(fileFilter) ?: emptyArray()).toMutableList()
            val events = mutableListOf<PostHogEvent>()
            val iterator = listFiles.iterator()

            while (iterator.hasNext()) {
                val file = iterator.next()

                try {
                    val inputStream = config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
                    val event = serializer.deserializeEvent(inputStream.reader().buffered())
                    event?.let {
                        events.add(event)
                    }
                } catch (e: Throwable) {
                    iterator.remove()
                    file.delete()
                    config.logger.log("File: ${file.name} failed to parse: $e.")
                }
            }

            if (events.isNotEmpty()) {
                var deleteFiles = true
                try {
                    api.batch(events)
                } catch (e: PostHogApiError) {
                    if (e.statusCode >= 400) {
                        // TODO: the reason to delete or not the files?
                    }
                } catch (e: IOException) {
                    // no connection should try again
                    if (e.isNetworkingError()) {
                        deleteFiles = false
                    }
                } finally {
                    if (deleteFiles) {
                        listFiles.forEach { file ->
                            file.delete()
                        }
                    }
                }
            }
        }
    }
}
