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
}
