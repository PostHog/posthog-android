package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.Date
import java.util.concurrent.ExecutorService

/**
 * The integration that send all the cached events, triggered once the SDK is setup
 * @property config the Config
 * @property api the API class
 * @property startDate the startDate cut off so we don't race with the Queue
 * @property executor the Executor
 */
internal class PostHogSendCachedEventsIntegration(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val startDate: Date,
    private val executor: ExecutorService,
) : PostHogIntegration {
    private companion object {
        @Volatile
        private var integrationInstalled = false
    }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled) {
            return
        }
        integrationInstalled = true

        executor.executeSafely {
            if (config.networkStatus?.isConnected() == false) {
                config.logger.log("Network isn't connected.")
                return@executeSafely
            }

            flushLegacyEvents()
            flushEvents(config.storagePrefix, PostHogApiEndpoint.BATCH)
            flushEvents(config.replayStoragePrefix, PostHogApiEndpoint.SNAPSHOT)
        }
        executor.shutdown()
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun flushLegacyEvents() {
        config.legacyStoragePrefix?.let {
            val legacyDir = File(it)
            val legacyFile = File(legacyDir, "${config.apiKey}.tmp")

            if (!legacyFile.existsSafely(config)) {
                return
            }

            var legacy: QueueFile? = null
            try {
                legacy =
                    QueueFile.Builder(legacyFile)
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
                            inputStream.use { theInputStream ->
                                val event = config.serializer.deserialize<PostHogEvent?>(theInputStream.reader().buffered())
                                event?.let {
                                    events.add(event)
                                    eventsCount++
                                } ?: run {
                                    removeFileSafely(iterator)
                                }
                            }
                        } catch (e: Throwable) {
                            removeFileSafely(iterator, e)
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
                                        // we delete the queue file because its empty
                                        legacyFile.deleteSafely(config)
                                        break
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
            } finally {
                try {
                    legacy?.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun deleteFileSafely(
        file: File,
        iterator: MutableIterator<File>,
        throwable: Throwable? = null,
    ) {
        config.logger.log("File: ${file.name} failed to parse: $throwable.")
        iterator.remove()
        file.deleteSafely(config)
    }

    private fun removeFileSafely(
        iterator: MutableIterator<ByteArray>,
        throwable: Throwable? = null,
    ) {
        config.logger.log("Event failed to parse: $throwable.")
        iterator.remove()
    }

    @Throws(PostHogApiError::class, IOException::class)
    private fun flushEvents(
        storagePrefix: String?,
        endpoint: PostHogApiEndpoint,
    ) {
        storagePrefix?.let {
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

                // sort by date asc so its sent in order
                listFiles.sortBy { file ->
                    file.lastModified()
                }

                while (listFiles.isNotEmpty()) {
                    val events = mutableListOf<PostHogEvent>()
                    val iterator = listFiles.iterator()
                    var eventsCount = 0

                    while (iterator.hasNext()) {
                        val file = iterator.next()

                        try {
                            val inputStream =
                                config.encryption?.decrypt(file.inputStream()) ?: file.inputStream()
                            inputStream.use { theInputStream ->
                                val event = config.serializer.deserialize<PostHogEvent?>(theInputStream.reader().buffered())
                                event?.let {
                                    events.add(event)
                                    eventsCount++
                                } ?: run {
                                    deleteFileSafely(file, iterator)
                                }
                            }
                        } catch (e: Throwable) {
                            deleteFileSafely(file, iterator, e)
                        }

                        // stop the while loop since the batch is full
                        if (events.size >= config.maxBatchSize) {
                            break
                        }
                    }

                    if (events.isNotEmpty()) {
                        var deleteFiles = true
                        try {
                            when (endpoint) {
                                PostHogApiEndpoint.BATCH -> api.batch(events)
                                PostHogApiEndpoint.SNAPSHOT -> api.snapshot(events)
                            }
                        } catch (e: PostHogApiError) {
                            deleteFiles = deleteFilesIfAPIError(e, config)

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

    override fun uninstall() {
        integrationInstalled = false
    }
}
