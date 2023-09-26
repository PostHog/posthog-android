package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.Date
import java.util.NoSuchElementException
import java.util.concurrent.Executors

internal class PostHogSendCachedEventsIntegration(private val config: PostHogConfig, private val api: PostHogApi, private val serializer: PostHogSerializer, private val startDate: Date) : PostHogIntegration {
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

    private fun flushLegacyEvents() {
        config.legacyStoragePrefix?.let {
            val legacyDir = File(it)
            val legacyFile = File(legacyDir, "${config.apiKey}.tmp")

            if (!legacyFile.existsSafely(config)) {
                return
            }

            try {
                val legacy = QueueFile.Builder(legacyFile)
                    .forceLegacy(true)
                    .build()

                while (!legacy.isEmpty) {
                    val events = mutableListOf<PostHogEvent>()

                    val iterator = legacy.iterator()
                    var eventsCount = 0
                    while (iterator.hasNext()) {
                        val eventBytes = iterator.next()

                        try {
                            val inputStream = config.encryption?.decrypt(eventBytes.inputStream()) ?: eventBytes.inputStream()
                            val event = serializer.deserializeEvent(inputStream.reader().buffered())
                            event?.let {
                                events.add(event)
                                eventsCount++
                            }
                        } catch (e: Throwable) {
                            iterator.remove()
                            config.logger.log("Event failed to parse: $e.")
                        }
                        // stop the while loop since the batch is full
                        if (events.size >= config.maxBatchSize) {
                            break
                        }
                    }

                    if (events.isNotEmpty()) {
                        var deleteFiles = true
                        try {
                            api.batch(events)
                        } catch (e: PostHogApiError) {
                            if (e.statusCode < 400) {
                                deleteFiles = false
                            }
                            throw e
                        } catch (e: IOException) {
                            // no connection should try again
                            if (e.isNetworkingError()) {
                                deleteFiles = false
                            }
                            throw e
                        } finally {
                            if (deleteFiles && eventsCount > 0) {
                                for (i in 1..eventsCount) {
                                    try {
                                        legacy.remove()
                                    } catch (e: NoSuchElementException) {
                                        // this should not happen but even if it does,
                                        // we delete the queue file then
                                        legacyFile.deleteSafely(config)
                                    } catch (e: Throwable) {
                                        config.logger.log("Error deleting file: $e.")
                                    }
                                }
                            }
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

            if (!dir.existsSafely(config)) {
                return
            }
            try {
                // so that we don't try to send events in this batch that is already in the queue
                // but just cached events
                val time = startDate.time
                val fileFilter = FileFilter { file -> file.lastModified() <= time }

                val listFiles = (dir.listFiles(fileFilter) ?: emptyArray()).toMutableList()

                while (listFiles.isNotEmpty()) {
                    val events = mutableListOf<PostHogEvent>()
                    val iterator = listFiles.iterator()
                    var eventsCount = 0

                    while (iterator.hasNext()) {
                        val file = iterator.next()

                        try {
                            val inputStream =
                                config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
                            val event = serializer.deserializeEvent(inputStream.reader().buffered())
                            event?.let {
                                events.add(event)
                                eventsCount++
                            }
                        } catch (e: Throwable) {
                            config.logger.log("File: ${file.name} failed to parse: $e.")
                            iterator.remove()
                            file.deleteSafely(config)
                        }

                        // stop the while loop since the batch is full
                        if (events.size >= config.maxBatchSize) {
                            break
                        }
                    }

                    if (events.isNotEmpty()) {
                        var deleteFiles = true
                        try {
                            api.batch(events)
                        } catch (e: PostHogApiError) {
                            if (e.statusCode < 400) {
                                deleteFiles = false
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
                                for (i in 1..eventsCount) {
                                    var file: File? = null
                                    try {
                                        file = listFiles.removeFirst()
                                        file.deleteSafely(config)
                                    } catch (e: Throwable) {
                                        config.logger.log("Failed to remove file: ${file?.name}: $e.")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Flushing events failed: $e.")
            }
        }
    }
}
