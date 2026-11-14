package com.posthog.server.openfeature

/**
 * Options for [PostHogProvider].
 *
 * @property defaultDistinctId Distinct ID to use when the evaluation context has no targeting key.
 *   When null, a missing targeting key returns the `TARGETING_KEY_MISSING` error.
 */
public class PostHogProviderOptions private constructor(
    public val defaultDistinctId: String?,
) {
    /**
     * Mutable builder for [PostHogProviderOptions].
     */
    public class Builder {
        /** Distinct ID to use when the evaluation context has no targeting key. */
        public var defaultDistinctId: String? = null

        /**
         * Sets the distinct ID to use when the evaluation context has no targeting key.
         *
         * @param defaultDistinctId Fallback distinct ID, for example `anonymous`.
         * @return This builder.
         */
        public fun defaultDistinctId(defaultDistinctId: String?): Builder {
            this.defaultDistinctId = defaultDistinctId
            return this
        }

        /**
         * Builds an immutable [PostHogProviderOptions] instance.
         *
         * @return Provider options containing the accumulated values.
         */
        public fun build(): PostHogProviderOptions = PostHogProviderOptions(defaultDistinctId)
    }

    public companion object {
        /**
         * Creates a new provider options builder.
         *
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
