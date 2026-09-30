package com.posthog.server

/**
 * Why local evaluation could not resolve a flag that has a loaded definition. Reported by
 * [PostHogFeatureFlagEvaluations.unresolvedFlags]. Each reason says what the caller can do about it.
 *
 * @property value The stable string for this reason, as defined by the SDK spec.
 */
public enum class PostHogUnresolvedFlagReason(public val value: String) {
    /** The flag has experience continuity enabled. Local evaluation never resolves it; change the flag. */
    EXPERIENCE_CONTINUITY("experience_continuity"),

    /**
     * The flag uses something the local evaluator does not support, such as a static cohort, an
     * unrecognized operator or a malformed value. Change the flag or upgrade the SDK.
     */
    UNSUPPORTED_DEFINITION("unsupported_definition"),

    /**
     * The call did not supply a property, group key or group property the flag's conditions need,
     * or supplied it in a form the evaluator cannot use. Pass it.
     */
    MISSING_CONTEXT("missing_context"),

    /**
     * A flag this flag depends on could not be resolved. Check the dependency's own reason; it appears
     * in [PostHogFeatureFlagEvaluations.unresolvedFlags] only when the dependency is in the requested scope.
     */
    UNRESOLVED_DEPENDENCY("unresolved_dependency"),
}
