package com.posthog.surveys

/**
 * Describes which question is shown after a given question is answered.
 */
public sealed class SurveyQuestionBranching {
    /** Advance to the next question in order. */
    public object Next : SurveyQuestionBranching()

    /** End the survey after this question. */
    public object End : SurveyQuestionBranching()

    /**
     * Branch to a different question depending on the user's answer.
     */
    public class ResponseBased(
        /** Maps a response value to the next question index (or to ending the survey). */
        public val responseValues: Map<String, Any>,
    ) : SurveyQuestionBranching()

    /**
     * Jump to a specific question by index.
     */
    public class SpecificQuestion(
        /** The index of the question to show next. */
        public val index: Int,
    ) : SurveyQuestionBranching()
}
