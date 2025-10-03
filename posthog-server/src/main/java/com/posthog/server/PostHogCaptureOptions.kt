package com.posthog.server

import java.time.Instant
import java.util.Date

/**
 * Provides an ergonomic interface when providing options for capturing events
 * This is mainly meant to be used from Java, as Kotlin can use named parameters.
 * @see <a href="https://posthog.com/docs/product-analytics/capture-events">Documentation: Capturing events</a>
 */
public class PostHogCaptureOptions private constructor(
    public val properties: Map<String, Any>?,
    public val userProperties: Map<String, Any>?,
    public val userPropertiesSetOnce: Map<String, Any>?,
    public val groups: Map<String, String>?,
    public val timestamp: Date? = null,
) {
    public class Builder {
        public var properties: MutableMap<String, Any>? = null
        public var userProperties: MutableMap<String, Any>? = null
        public var userPropertiesSetOnce: MutableMap<String, Any>? = null
        public var groups: MutableMap<String, String>? = null
        public var timestamp: Date? = null

        /**
         * Add a single custom property to the capture options
         */
        public fun property(
            key: String,
            value: Any,
        ): Builder {
            properties =
                (properties ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple custom properties to the capture options
         */
        public fun properties(properties: Map<String, Any>): Builder {
            this.properties =
                (this.properties ?: mutableMapOf()).apply {
                    putAll(properties)
                }
            return this
        }

        /**
         * Adds a single user property to the capture options
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userProperty(
            key: String,
            value: Any,
        ): Builder {
            this.userProperties =
                (this.userProperties ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple user properties to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userProperties(userProperties: Map<String, Any>): Builder {
            this.userProperties =
                (this.userProperties ?: mutableMapOf()).apply {
                    putAll(userProperties)
                }
            return this
        }

        /**
         * Adds a single user property (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userPropertySetOnce(
            key: String,
            value: Any,
        ): Builder {
            this.userPropertiesSetOnce =
                (this.userPropertiesSetOnce ?: mutableMapOf()).apply {
                    put(key, value)
                }
            return this
        }

        /**
         * Appends multiple user properties (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userPropertiesSetOnce(userPropertiesSetOnce: Map<String, Any>): Builder {
            this.userPropertiesSetOnce =
                (this.userPropertiesSetOnce ?: mutableMapOf()).apply {
                    putAll(userPropertiesSetOnce)
                }
            return this
        }

        /**
         * Adds a single group to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/group-analytics">Documentation: Group Analytics</a>
         */
        public fun group(
            type: String,
            key: String,
        ): Builder {
            this.groups =
                (this.groups ?: mutableMapOf()).apply {
                    put(type, key)
                }
            return this
        }

        /**
         * Appends multiple groups to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/group-analytics">Documentation: Group Analytics</a>
         */
        public fun groups(groups: Map<String, String>): Builder {
            this.groups =
                (this.groups ?: mutableMapOf()).apply {
                    putAll(groups)
                }
            return this
        }

        /**
         * Override the timestamp for the event.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(date: Date): Builder {
            this.timestamp = date
            return this
        }

        /**
         * Override the timestamp for the event.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(epochMillis: Long): Builder {
            this.timestamp = Date(epochMillis)
            return this
        }

        /**
         * Override the timestamp for the event.
         * @see <a href="https://posthog.com/docs/data/timestamps">Documentation: Timestamps</a>
         */
        public fun timestamp(instant: Instant): Builder {
            this.timestamp = Date(instant.toEpochMilli())
            return this
        }

        public fun build(): PostHogCaptureOptions =
            PostHogCaptureOptions(
                properties,
                userProperties,
                userPropertiesSetOnce,
                groups,
                timestamp,
            )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
