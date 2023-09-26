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

//            val test = "{\n" +
//                    "  \"event\": \"\$screen\",\n" +
//                    "  \"uuid\": \"9cf74087-356a-41c3-9ff5-754ed31dc320\",\n" +
//                    "  \"timestamp\": \"2023-09-20T11:59:31Z\",\n" +
//                    "  \"properties\": {\n" +
//                    "    \"\$device_manufacturer\": \"Google\",\n" +
//                    "    \"\$os_version\": \"13\",\n" +
//                    "    \"\$screen_density\": 2.625,\n" +
//                    "    \"\$timezone\": \"Europe/Vienna\",\n" +
//                    "    \"\$locale\": \"en-US\",\n" +
//                    "    \"\$screen_width\": 1080,\n" +
//                    "    \"\$os_name\": \"Android\",\n" +
//                    "    \"\$screen_height\": 2274,\n" +
//                    "    \"\$user_agent\": \"Dalvik/2.1.0 (Linux; U; Android 13; sdk_gphone64_arm64 Build/TE1A.220922.012)\",\n" +
//                    "    \"\$app_version\": \"1.0\",\n" +
//                    "    \"\$lib\": \"posthog-android\",\n" +
//                    "    \"\$device_name\": \"emu64a\",\n" +
//                    "    \"\$network_carrier\": \"T-Mobile\",\n" +
//                    "    \"\$app_name\": \"My Application\",\n" +
//                    "    \"\$device_model\": \"sdk_gphone64_arm64\",\n" +
//                    "    \"\$lib_version\": \"version\",\n" +
//                    "    \"\$app_namespace\": \"com.posthog.myapplication\",\n" +
//                    "    \"\$app_build\": \"1\",\n" +
//                    "    \"myProperty\": \"myValue\",\n" +
//                    "    \"\$feature/4535-funnel-bar-viz\": true,\n" +
//                    "    \"\$active_feature_flags\": [\n" +
//                    "      \"4535-funnel-bar-viz\"\n" +
//                    "    ],\n" +
//                    "    \"\$screen_name\": \"sreeenn\"\n" +
//                    "  },\n" +
//                    "  \"distinct_id\": \"my_identify\"\n" +
//                    "}\n"

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
