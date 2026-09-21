package com.posthog.surveys

/**
 * Optional capability for delegates that restore unfinished surveys.
 *
 * Delegates without this capability, or returning false, start fresh at question 0.
 * Opting in requires starting at [PostHogDisplaySurvey.initialQuestionIndex]; the SDK
 * restores saved answers and the submission ID for that attempt.
 *
 * For example, a renderer that honors the initial question can declare:
 * `override val supportsSurveyResume: Boolean = true`.
 */
public interface PostHogSurveysResumeAwareDelegate : PostHogSurveysDelegate {
    public val supportsSurveyResume: Boolean
}
