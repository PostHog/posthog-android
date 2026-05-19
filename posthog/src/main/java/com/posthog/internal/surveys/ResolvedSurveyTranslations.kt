package com.posthog.internal.surveys

import com.posthog.PostHogInternal
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyQuestionTranslation
import com.posthog.surveys.SurveyTranslation

/**
 * [matchedKey] is the original-cased key from a `translations` dictionary that drove
 * the change (preferring the survey-level key when both survey and question matched),
 * or `null` when no user-visible field actually changed. [questions] is indexed
 * positionally against [Survey.questions].
 */
@PostHogInternal
public data class ResolvedSurveyTranslations(
    val matchedKey: String?,
    val survey: SurveyTranslation?,
    val questions: List<SurveyQuestionTranslation?>,
)
