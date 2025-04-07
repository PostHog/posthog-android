package com.posthog.android.surveys

import android.content.Context
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.internal.getDeviceType
import com.posthog.android.internal.isMatchingRegex
import com.posthog.surveys.Survey
import com.posthog.surveys.SurveyMatchType
import com.posthog.surveys.SurveyType

public class PostHogSurveysIntegration(private val context: Context) : PostHogIntegration {
    private val surveyValidationMap: Map<SurveyMatchType, (List<String>, String) -> Boolean> =
        mapOf(
            SurveyMatchType.I_CONTAINS to { targets, value -> targets.any { value.contains(it, ignoreCase = true) } },
            SurveyMatchType.NOT_I_CONTAINS to { targets, value -> targets.all { !value.contains(it, ignoreCase = true) } },
            SurveyMatchType.REGEX to { targets, value -> targets.any { isMatchingRegex(value, it) } },
            SurveyMatchType.NOT_REGEX to { targets, value -> targets.all { !isMatchingRegex(value, it) } },
            SurveyMatchType.EXACT to { targets, value -> targets.any { value == it } },
            SurveyMatchType.IS_NOT to { targets, value -> targets.all { value != it } },
        )

    private val deviceType by lazy {
        getDeviceType(context) ?: "Mobile"
    }

    private var postHog: PostHogInterface? = null

    public override fun install(postHog: PostHogInterface) {
        this.postHog = postHog
    }

    private fun defaultMatchType(matchType: SurveyMatchType?): SurveyMatchType {
        return matchType ?: SurveyMatchType.I_CONTAINS
    }

    private fun doesSurveyDeviceTypesMatch(survey: Survey): Boolean {
        val deviceTypes = survey.conditions?.deviceTypes ?: return true
        if (deviceTypes.isEmpty()) return true

        val matchType = defaultMatchType(survey.conditions?.deviceTypesMatchType)
        return surveyValidationMap[matchType]?.invoke(deviceTypes, deviceType) ?: true
    }

    private fun canActivateRepeatedly(survey: Survey): Boolean {
        if (survey.type == SurveyType.WIDGET) {
            return true
        }

        return survey.conditions?.events?.repeatedActivation ?: true
    }

    private fun getActiveMatchingSurveys(surveys: List<Survey>): List<Survey> {
        return surveys.filter { survey ->
            if (survey.startDate == null || survey.endDate != null) return@filter false

            if (!doesSurveyDeviceTypesMatch(survey)) return@filter false

            // TODO: add support for seen surveys

            if (!canActivateRepeatedly(survey)) return@filter false

            if (survey.linkedFlagKey.isNullOrEmpty() &&
                survey.targetingFlagKey.isNullOrEmpty() &&
                survey.internalTargetingFlagKey.isNullOrEmpty() &&
                survey.featureFlagKeys.isNullOrEmpty()
            ) {
                return@filter true
            }

            val postHog = postHog ?: return@filter false

            val linkedFlagCheck = survey.linkedFlagKey?.let { postHog.isFeatureEnabled(it) } ?: true
            val targetingFlagCheck = survey.targetingFlagKey?.let { postHog.isFeatureEnabled(it) } ?: true
            val internalTargetingFlagKey = survey.internalTargetingFlagKey
            val internalTargetingFlagCheck =
                if (!internalTargetingFlagKey.isNullOrEmpty()) {
                    postHog.isFeatureEnabled(internalTargetingFlagKey)
                } else {
                    true
                }

            val flagsCheck =
                survey.featureFlagKeys?.all { keyVal ->
                    val key = keyVal.key
                    val value = keyVal.value
                    key.isEmpty() || value.isNullOrEmpty() || postHog.isFeatureEnabled(value)
                } ?: true

            linkedFlagCheck && targetingFlagCheck && internalTargetingFlagCheck && flagsCheck
        }
    }
}
