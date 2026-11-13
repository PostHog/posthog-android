package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.PostHogVisibleForTesting
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The integration that sends all the cached legacy events through capture v1, triggered once the
 * SDK is setup
 * @property config the Config
 * @property api the API class
 * @property executor the Executor
 */
internal class PostHogSendCachedEventsIntegration(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val executor: ExecutorService,
) : PostHogIntegration {
    private var ownsInstallation = false

    internal companion object {
        private val integrationInstalled = AtomicBoolean(false)

        @PostHogVisibleForTesting
        internal fun resetInstallationForTesting() {
            integrationInstalled.set(false)
        }
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        if (!integrationInstalled.compareAndSet(false, true)) {
            executor.shutdown()
            return
        }
        ownsInstallation = true

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
            val legacyFile = File(legacyDir, "${config.projectToken}.tmp")

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
                    // The stored bytes of each event in `events`, to re-queue the ones capture asks to retry.
                    val eventsBytes = mutableListOf<ByteArray>()

                    val iterator = legacy.iterator()
                    var eventsCount = 0
                    var batchBytes = 0L
                    while (iterator.hasNext()) {
                        val eventBytes = iterator.next()
                        // Checked before adding, as the queue does: a non-empty batch is sent before
                        // it would pass the capture v1 byte target, so order is kept and an event
                        // over the target ships alone. The event stays at the head for the next batch.
                        if (events.isNotEmpty() && batchBytes + eventBytes.size > CAPTURE_V1_MAX_BATCH_BYTES) {
                            break
                        }

                        try {
                            val inputStream = config.encryption?.decrypt(eventBytes.inputStream()) ?: eventBytes.inputStream()
                            inputStream.use { theInputStream ->
                                val event = config.serializer.deserialize<PostHogEvent?>(theInputStream.reader().buffered())
                                event?.let {
                                    events.add(normalizeLegacyEvent(event))
                                    eventsBytes.add(eventBytes)
                                    eventsCount++
                                    batchBytes += eventBytes.size
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
                        var retryBytes = emptyList<ByteArray>()
                        try {
                            val retryUuids = api.captureV1(events).retryIds
                            if (retryUuids.isNotEmpty()) {
                                retryBytes = eventsBytes.filterIndexed { index, _ -> events[index].uuid?.toString() in retryUuids }
                            }
                        } catch (e: PostHogApiError) {
                            if (isCaptureV1RetriableStatusCode(e.statusCode)) {
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
                            if (deleteFiles && retryBytes.isNotEmpty()) {
                                // The legacy file is FIFO: re-queue the events capture asked to retry
                                // at its tail first, so they stay durable while the delivered ones are
                                // removed from the head below. They're sent on the next launch.
                                try {
                                    retryBytes.forEach { legacy.add(it) }
                                } catch (e: Throwable) {
                                    config.logger.log("Error re-queuing legacy events: $e.")
                                    deleteFiles = false
                                }
                            }
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
                        if (retryBytes.isNotEmpty()) {
                            config.logger.log("Kept ${retryBytes.size} legacy events for retry.")
                            return
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

    /**
     * Events cached by 2.x carry their uuid as `message_id` and person properties as a root `$set`,
     * which capture v1 doesn't read: a missing uuid fails the whole request, and the properties
     * would be lost. Moves both where capture v1 expects them.
     */
    @Suppress("DEPRECATION")
    private fun normalizeLegacyEvent(event: PostHogEvent): PostHogEvent {
        val uuid = event.uuid ?: event.messageId ?: TimeBasedEpochGenerator.generate()
        val set = event.set
        if (uuid == event.uuid && set == null) {
            return event
        }
        val properties = event.properties ?: mutableMapOf()
        if (set != null && !properties.containsKey("\$set")) {
            properties["\$set"] = set
        }
        return event.copy(uuid = uuid, properties = properties, messageId = null, set = null)
    }

    private fun removeFileSafely(
        iterator: MutableIterator<ByteArray>,
        throwable: Throwable? = null,
    ) {
        config.logger.log("Event failed to parse: $throwable.")
        iterator.remove()
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        ownsInstallation = false
        integrationInstalled.set(false)
    }
}
