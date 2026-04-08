package com.posthog.android.replay

import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.internal.PostHogQueueInterface
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.io.File
import java.util.UUID

/**
 * A disk-based buffer queue for session replay snapshots.
 *
 * Uses UUID v7 filenames so timestamps can be extracted from filenames
 * for duration calculations.
 */
internal class PostHogReplayBufferQueue(
    private val config: PostHogConfig,
    private val bufferDir: File,
) {
    private val items = mutableListOf<String>()
    private val itemsLock = Any()

    val depth: Int
        get() = synchronized(itemsLock) { items.size }

    /**
     * Returns the time span (in millis) between the oldest and newest buffered items,
     * based on the UUID v7 embedded timestamps.
     */
    val bufferDurationMs: Long?
        get() =
            synchronized(itemsLock) {
                val oldest = items.firstOrNull() ?: return@synchronized null
                val newest = items.lastOrNull() ?: return@synchronized null
                val oldestTs = timestampFromUUIDv7(oldest) ?: return@synchronized null
                val newestTs = timestampFromUUIDv7(newest) ?: return@synchronized null
                maxOf(newestTs - oldestTs, 0)
            }

    init {
        setup()
    }

    private fun setup() {
        // Clear any leftover buffer from previous sessions — if they're still here,
        // they didn't meet the minimum duration threshold and should be discarded.
        deleteDirectorySafely(bufferDir)

        try {
            bufferDir.mkdirs()
        } catch (e: Throwable) {
            config.logger.log("Error trying to create replay buffer folder: $e")
        }

        synchronized(itemsLock) {
            items.clear()
        }
    }

    private fun deleteDirectorySafely(dir: File) {
        try {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (e: Throwable) {
            config.logger.log("Error deleting replay buffer directory: $e")
        }
    }

    fun add(event: PostHogEvent) {
        try {
            val filename = "${TimeBasedEpochGenerator.generate()}.event"
            val file = File(bufferDir, filename)
            val os = config.encryption?.encrypt(file.outputStream()) ?: file.outputStream()
            os.use { output ->
                config.serializer.serialize(event, output.writer().buffered())
            }
            synchronized(itemsLock) { items.add(filename) }
        } catch (e: Throwable) {
            config.logger.log("Could not write replay buffer file: $e")
        }
    }

    /**
     * Migrates all buffered items to the target queue by deserializing each buffered
     * event and re-adding it to the target queue.
     *
     * Returns the number of events successfully migrated.
     */
    fun migrateAllTo(targetQueue: PostHogQueueInterface): Int {
        val itemsToMigrate: List<String> =
            synchronized(itemsLock) {
                val copy = items.toList()
                items.clear()
                copy
            }

        var migratedCount = 0
        for (item in itemsToMigrate) {
            val sourceFile = File(bufferDir, item)
            try {
                val input = config.encryption?.decrypt(sourceFile.inputStream()) ?: sourceFile.inputStream()
                input.use {
                    val event = config.serializer.deserialize<PostHogEvent?>(it.reader().buffered())
                    if (event != null) {
                        targetQueue.add(event)
                        migratedCount++
                    }
                }
            } catch (e: Throwable) {
                config.logger.log("Failed to migrate replay buffer item $item: $e")
            } finally {
                deleteFileSafely(sourceFile)
            }
        }

        return migratedCount
    }

    /**
     * Removes all buffered items from disk and memory.
     */
    fun clear() {
        setup()
    }

    private fun deleteFileSafely(file: File) {
        try {
            file.delete()
        } catch (e: Throwable) {
            config.logger.log("Error deleting replay buffer file ${file.name}: $e")
        }
    }

    companion object {
        /**
         * Extracts the millisecond epoch timestamp from a UUID v7 filename.
         *
         * UUID v7 encodes Unix milliseconds in the first 48 bits.
         * The filename format is `<uuid>.event`.
         *
         * We parse the UUID and extract millis via `mostSignificantBits ushr 16`.
         *
         * @return millis since epoch, or null if parsing fails
         */
        internal fun timestampFromUUIDv7(filename: String): Long? {
            return try {
                val uuidString = filename.removeSuffix(".event")
                val uuid = UUID.fromString(uuidString)
                uuid.mostSignificantBits ushr 16
            } catch (_: Throwable) {
                null
            }
        }
    }
}
