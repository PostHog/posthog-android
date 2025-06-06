package com.posthog.surveys

private fun isMatchingRegex(
    value: String,
    pattern: String,
): Boolean {
    return try {
        Regex(pattern).containsMatchIn(value)
    } catch (e: Throwable) {
        false
    }
}

private val surveyValidationMap: Map<SurveyMatchType, (List<String>, String) -> Boolean> =
    mapOf(
        SurveyMatchType.I_CONTAINS to { targets, value -> targets.any { target -> value.lowercase().contains(target.lowercase()) } },
        SurveyMatchType.NOT_I_CONTAINS to { targets, value -> targets.all { target -> !value.lowercase().contains(target.lowercase()) } },
        SurveyMatchType.REGEX to { targets, value -> targets.any { target -> isMatchingRegex(value, target) } },
        SurveyMatchType.NOT_REGEX to { targets, value -> targets.all { target -> !isMatchingRegex(value, target) } },
        SurveyMatchType.EXACT to { targets, value -> targets.any { target -> value == target } },
        SurveyMatchType.IS_NOT to { targets, value -> targets.all { target -> value != target } },
    )

private fun defaultMatchType(matchType: SurveyMatchType?): SurveyMatchType {
    return matchType ?: SurveyMatchType.I_CONTAINS
}

private fun doesSurveyDeviceTypesMatch(
    survey: Survey,
    deviceType: String,
): Boolean {
    val deviceTypes = survey.conditions?.deviceTypes ?: return true
    if (deviceTypes.isEmpty()) return true

    // TODO: check if it should return false
    return surveyValidationMap[defaultMatchType(survey.conditions.deviceTypesMatchType)]?.invoke(
        deviceTypes,
        deviceType,
    ) ?: return true
}

private fun canActivateRepeatedly(survey: Survey): Boolean {
    return survey.conditions?.events?.repeatedActivation == true && hasEvents(survey)
}

private fun hasEvents(survey: Survey): Boolean {
    return survey.conditions?.events?.values?.isNotEmpty() == true
}

private fun getActiveMatchingSurveys(
    surveys: List<Survey>,
    flags: Map<String, Any>,
    seenSurveys: List<String>,
    activatedSurveys: Set<String>,
    deviceType: String,
): List<Survey> {
    return surveys.filter { survey ->
        // Is Active
        if (survey.startDate == null || survey.endDate != null) {
            return@filter false
        }

        // Device type check
        if (!doesSurveyDeviceTypesMatch(survey, deviceType)) {
            return@filter false
        }

        if (seenSurveys.contains(survey.id) && !canActivateRepeatedly(survey)) {
            return@filter false
        }

        // URL and CSS selector conditions are currently ignored

        if (
            survey.linkedFlagKey == null &&
            survey.targetingFlagKey == null &&
            survey.internalTargetingFlagKey == null &&
            survey.featureFlagKeys.isNullOrEmpty()
        ) {
            // Survey is targeting All Users with no conditions
            return@filter true
        }

        val linkedFlagCheck = survey.linkedFlagKey?.let { flags[it] == true } ?: true
        val targetingFlagCheck = survey.targetingFlagKey?.let { flags[it] == true } ?: true

        val eventBasedTargetingFlagCheck = if (hasEvents(survey)) activatedSurveys.contains(survey.id) else true

        val internalTargetingFlagCheck =
            survey.internalTargetingFlagKey?.let {
                if (!canActivateRepeatedly(survey)) flags[it] == true else true
            } ?: true

        val flagsCheck =
            survey.featureFlagKeys?.all { (key, value) ->
                key.isNotEmpty() && value?.isNotEmpty() == true && flags[value] == true
            } ?: true

        linkedFlagCheck && targetingFlagCheck && internalTargetingFlagCheck && eventBasedTargetingFlagCheck && flagsCheck
    }
}
