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

    /**
     * When enabled, surveys without explicit device-type targeting are excluded.
     *
     * If `true`, surveys with missing or empty [SurveyConditions.deviceTypes] are ineligible.
     * Surveys with a non-empty device-type condition continue to use the existing match
     * operators against the current device type (`Mobile`, `Tablet`, or `TV`).
     *
     * Default: `false` (preserve existing allow-when-unspecified behavior).
     */
    public var requireDeviceTypeTargeting: Boolean = false
}
