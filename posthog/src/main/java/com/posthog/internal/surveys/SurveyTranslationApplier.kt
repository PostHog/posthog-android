@file:Suppress("ktlint:standard:filename")

package com.posthog.internal.surveys

import com.posthog.PostHogInternal
import com.posthog.surveys.LinkSurveyQuestion
import com.posthog.surveys.MultipleSurveyQuestion
import com.posthog.surveys.RatingSurveyQuestion
import com.posthog.surveys.SingleSurveyQuestion
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyQuestion
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

/**
 * Returns a non-null [ResolvedSurveyTranslations.matchedKey] only when applying the
 * matched translation would actually change a user-visible field — so callers don't
 * stamp `$survey_language` onto events when nothing on screen changed.
 */
@PostHogInternal
public fun resolveSurveyTranslations(
    survey: Survey,
    targetLanguage: String?,
): ResolvedSurveyTranslations {
    val empty =
        ResolvedSurveyTranslations(
            matchedKey = null,
            survey = null,
            questions = survey.questions.map { null },
        )
    if (targetLanguage.isNullOrBlank()) return empty

    val surveyKey = findBestTranslationMatch(survey.translations, targetLanguage)
    val surveyTranslation = surveyKey?.let { survey.translations?.get(it) }
    val surveyChanged = surveyTranslation != null && surveyTranslationChangesAnything(survey, surveyTranslation)

    val questionMatches: List<Pair<String?, SurveyQuestionTranslation?>> =
        survey.questions.map { question ->
            val key = findBestTranslationMatch(question.translations, targetLanguage)
            val translation = key?.let { question.translations?.get(it) }
            val changes = translation != null && questionTranslationChangesAnything(question, translation)
            if (changes) key to translation else null to null
        }

    val anyQuestionChanged = questionMatches.any { it.second != null }

    if (!surveyChanged && !anyQuestionChanged) return empty

    val matchedKey =
        when {
            surveyChanged -> surveyKey
            else -> questionMatches.firstNotNullOfOrNull { it.first }
        }

    return ResolvedSurveyTranslations(
        matchedKey = matchedKey,
        survey = if (surveyChanged) surveyTranslation else null,
        questions = questionMatches.map { it.second },
    )
}

private fun surveyTranslationChangesAnything(
    survey: Survey,
    translation: SurveyTranslation,
): Boolean {
    if (translation.name != null && translation.name != survey.name) return true
    val appearance = survey.appearance
    if (translation.thankYouMessageHeader != null && translation.thankYouMessageHeader != appearance?.thankYouMessageHeader) return true
    if (translation.thankYouMessageDescription != null &&
        translation.thankYouMessageDescription != appearance?.thankYouMessageDescription
    ) {
        return true
    }
    if (translation.thankYouMessageCloseButtonText != null &&
        translation.thankYouMessageCloseButtonText != appearance?.thankYouMessageCloseButtonText
    ) {
        return true
    }
    return false
}

private fun questionTranslationChangesAnything(
    question: SurveyQuestion,
    translation: SurveyQuestionTranslation,
): Boolean {
    if (translation.question != null && translation.question != question.question) return true
    if (translation.description != null && translation.description != question.description) return true
    if (translation.buttonText != null && translation.buttonText != question.buttonText) return true
    when (question) {
        is LinkSurveyQuestion ->
            if (translation.link != null && translation.link != question.link) return true
        is RatingSurveyQuestion -> {
            if (translation.lowerBoundLabel != null && translation.lowerBoundLabel != question.lowerBoundLabel) return true
            if (translation.upperBoundLabel != null && translation.upperBoundLabel != question.upperBoundLabel) return true
        }
        is SingleSurveyQuestion ->
            if (translation.choices != null && translation.choices != question.choices) return true
        is MultipleSurveyQuestion ->
            if (translation.choices != null && translation.choices != question.choices) return true
    }
    return false
}
