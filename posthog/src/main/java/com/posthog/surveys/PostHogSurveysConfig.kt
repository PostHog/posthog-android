package com.posthog.surveys

/**
 * Configuration for PostHog Surveys feature.
 */
public class PostHogSurveysConfig {
    /**
     * Delegate responsible for managing survey presentation in your app.
     * Handles survey rendering, response collection, and lifecycle events.
     * You can provide your own delegate for a custom survey presentation.
     *
     * Defaults to [PostHogSurveysDefaultDelegate] which provides logging but no UI at the moment.
     */
    public var surveysDelegate: PostHogSurveysDelegate = PostHogSurveysDefaultDelegate()

    /**
     * Optional explicit override for the language used when rendering surveys.
     *
     * When set, surveys with matching entries in [Survey.translations] will be rendered
     * in this language regardless of the device locale or any `language` person property.
     *
     * Format: a language tag such as "fr", "pt-BR", "zh-CN". Matching is case-insensitive
     * and falls back to the base language (e.g. "pt" if "pt-BR" is requested but only "pt"
     * is provided).
     *
     * Blank or null values are treated as unset.
     *
     * Default: `null`.
     */
    public var overrideDisplayLanguage: String? = null
}
