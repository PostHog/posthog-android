package com.posthog

/**
 * Provides an ergonomic interface when providing options for capturing events
 * This is mainly meant to be used from Java, as Kotlin can use named parameters.
 * @see <a href="https://posthog.com/docs/product-analytics/capture-events">Documentation: Capturing events</a>
 */
public class CaptureOptions private constructor(
    public val properties: Map<String, Any>?,
    public val userProperties: Map<String, Any>?,
    public val userPropertiesSetOnce: Map<String, Any>?,
    public val groups: Map<String, String>?,
) {
    public class Builder {
        public var properties: Map<String, Any>? = null
        public var userProperties: Map<String, Any>? = null
        public var userPropertiesSetOnce: Map<String, Any>? = null
        public var groups: Map<String, String>? = null

        /**
         * Add a single custom property to the capture options
         */
        public fun property(
            key: String,
            value: Any,
        ): Builder {
            if (properties == null) {
                properties = mutableMapOf()
            }
            properties!![key] = value
            return this
        }

        /**
         * Appends multiple custom properties to the capture options
         */
        public fun properties(properties: Map<String, Any>): Builder {
            if (this.properties == null) {
                this.properties = mutableMapOf()
            }
            this.properties!!.putAll(properties)
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
            if (userProperties == null) {
                userProperties = mutableMapOf()
            }
            userProperties!![key] = value
            return this
        }

        /**
         * Appends multiple user properties to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userProperties(userProperties: Map<String, Any>): Builder {
            if (this.userProperties == null) {
                this.userProperties = mutableMapOf()
            }
            this.userProperties!!.putAll(userProperties)
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
            if (userPropertiesSetOnce == null) {
                userPropertiesSetOnce = mutableMapOf()
            }
            userPropertiesSetOnce!![key] = value
            return this
        }

        /**
         * Appends multiple user properties (set once) to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/user-properties">Documentation: User Properties</a>
         */
        public fun userPropertiesSetOnce(userPropertiesSetOnce: Map<String, Any>): Builder {
            if (this.userPropertiesSetOnce == null) {
                this.userPropertiesSetOnce = mutableMapOf()
            }
            this.userPropertiesSetOnce!!.putAll(userPropertiesSetOnce)
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
            if (groups == null) {
                groups = mutableMapOf()
            }
            groups!![type] = key
            return this
        }

        /**
         * Appends multiple groups to the capture options.
         * @see <a href="https://posthog.com/docs/product-analytics/group-analytics">Documentation: Group Analytics</a>
         */
        public fun groups(groups: Map<String, String>): Builder {
            if (this.groups == null) {
                this.groups = mutableMapOf()
            }
            this.groups!!.putAll(groups)
            return this
        }

        public fun build(): CaptureOptions = CaptureOptions(properties, userProperties, userPropertiesSetOnce, groups)
    }

    public companion object {
        @JvmStatic public fun builder(): Builder = Builder()

        @JvmSynthetic
        public operator fun invoke(block: Builder.() -> Unit): CaptureOptions = Builder().apply(block).build()
    }
}
