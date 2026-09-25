package com.posthog.server

/**
 * Criteria for [PostHogFeatureFlagEvaluations.only]. A flag is kept when it satisfies every
 * criterion that is set; a null criterion does not constrain the result. Filtering happens in
 * memory on the snapshot and never re-evaluates a flag.
 *
 * Kotlin callers can use the constructor with named arguments; Java callers should use [builder].
 *
 * @property keys Flag keys to keep. Unknown keys are dropped with a warning, as in `only(keys)`.
 * @property evaluationRuntimes Evaluation runtimes to keep, as reported by `/local_evaluation`:
 *   `"all"`, `"client"` or `"server"`. A flag whose runtime is unknown (null) is dropped, because
 *   unknown does not mean client-safe. See [PostHogFeatureFlagEvaluations.getEvaluationRuntime].
 */
public class PostHogFeatureFlagFilter(
    public val keys: Collection<String>? = null,
    public val evaluationRuntimes: Collection<String>? = null,
) {
    /**
     * Mutable builder for [PostHogFeatureFlagFilter].
     */
    public class Builder {
        /** Flag keys to keep. */
        public var keys: MutableList<String>? = null

        /** Evaluation runtimes to keep. */
        public var evaluationRuntimes: MutableList<String>? = null

        /**
         * Keeps only the given flag keys.
         *
         * @param keys Feature flag keys to keep.
         * @return This builder.
         */
        public fun keys(keys: List<String>): Builder {
            this.keys = this.keys.addBuilderValues(keys)
            return this
        }

        /**
         * Keeps only the given flag keys.
         *
         * @param keys Feature flag keys to keep.
         * @return This builder.
         */
        public fun keys(vararg keys: String): Builder = keys(keys.toList())

        /**
         * Keeps only flags configured for the given evaluation runtimes.
         *
         * @param evaluationRuntimes Runtimes to keep: `"all"`, `"client"` or `"server"`.
         * @return This builder.
         */
        public fun evaluationRuntimes(evaluationRuntimes: List<String>): Builder {
            this.evaluationRuntimes = this.evaluationRuntimes.addBuilderValues(evaluationRuntimes)
            return this
        }

        /**
         * Keeps only flags configured for the given evaluation runtimes.
         *
         * @param evaluationRuntimes Runtimes to keep: `"all"`, `"client"` or `"server"`.
         * @return This builder.
         */
        public fun evaluationRuntimes(vararg evaluationRuntimes: String): Builder = evaluationRuntimes(evaluationRuntimes.toList())

        /**
         * Builds an immutable [PostHogFeatureFlagFilter] instance.
         *
         * @return A filter containing the accumulated criteria.
         */
        public fun build(): PostHogFeatureFlagFilter =
            PostHogFeatureFlagFilter(
                keys.toBuilderListSnapshot(),
                evaluationRuntimes.toBuilderListSnapshot(),
            )
    }

    public companion object {
        /**
         * Creates a new Java-friendly filter builder.
         *
         * @return A new [Builder].
         */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
