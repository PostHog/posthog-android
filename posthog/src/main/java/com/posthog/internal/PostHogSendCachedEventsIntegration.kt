package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * The integration that sends all the cached legacy events, triggered once the SDK is setup
 * @property config the Config
 * @property api the API class
 * @property executor the Executor
 */
internal class PostHogSendCachedEventsIntegration(
    private val config: PostHogConfig,
    private val api: PostHogApi,
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

    private fun removeFileSafely(
        iterator: MutableIterator<ByteArray>,
        throwable: Throwable? = null,
    ) {
        config.logger.log("Event failed to parse: $throwable.")
        iterator.remove()
    }

    override fun uninstall() {
        integrationInstalled = false
    }
}
