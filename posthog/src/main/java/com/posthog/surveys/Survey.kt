package com.posthog.surveys

import com.google.gson.annotations.SerializedName
import java.util.Date

public data class Survey(
    val id: String,
    val name: String,
    val type: SurveyType,
    val questions: List<SurveyQuestion>,
    val description: String?,
    @SerializedName("feature_flag_keys")
    val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?,
    @SerializedName("linked_flag_key")
    val linkedFlagKey: String?,
    @SerializedName("targeting_flag_key")
    val targetingFlagKey: String?,
    @SerializedName("internal_targeting_flag_key")
    val internalTargetingFlagKey: String?,
    val conditions: SurveyConditions?,
    val appearance: SurveyAppearance?,
    @SerializedName("current_iteration")
    val currentIteration: Int?,
    @SerializedName("current_iteration_start_date")
    val currentIterationStartDate: Date?,
    @SerializedName("start_date")
    val startDate: Date?,
    @SerializedName("end_date")
    val endDate: Date?,
    val schedule: SurveySchedule?,
    val translations: Map<String, SurveyTranslation>? = null,
    @SerializedName("enable_partial_responses")
    val enablePartialResponses: Boolean? = null,
) {
    // Kotlin default arguments also have JVM signatures; retain both the old constructor
    // and copy overload so already-compiled consumers can upgrade without recompiling.
    public constructor(
        id: String,
        name: String,
        type: SurveyType,
        questions: List<SurveyQuestion>,
        description: String?,
        featureFlagKeys: List<SurveyFeatureFlagKeyValue>?,
        linkedFlagKey: String?,
        targetingFlagKey: String?,
        internalTargetingFlagKey: String?,
        conditions: SurveyConditions?,
        appearance: SurveyAppearance?,
        currentIteration: Int?,
        currentIterationStartDate: Date?,
        startDate: Date?,
        endDate: Date?,
        schedule: SurveySchedule?,
        translations: Map<String, SurveyTranslation>? = null,
    ) : this(
        id = id,
        name = name,
        type = type,
        questions = questions,
        description = description,
        featureFlagKeys = featureFlagKeys,
        linkedFlagKey = linkedFlagKey,
        targetingFlagKey = targetingFlagKey,
        internalTargetingFlagKey = internalTargetingFlagKey,
        conditions = conditions,
        appearance = appearance,
        currentIteration = currentIteration,
        currentIterationStartDate = currentIterationStartDate,
        startDate = startDate,
        endDate = endDate,
        schedule = schedule,
        translations = translations,
        enablePartialResponses = null,
    )

    public fun copy(
        id: String = this.id,
        name: String = this.name,
        type: SurveyType = this.type,
        questions: List<SurveyQuestion> = this.questions,
        description: String? = this.description,
        featureFlagKeys: List<SurveyFeatureFlagKeyValue>? = this.featureFlagKeys,
        linkedFlagKey: String? = this.linkedFlagKey,
        targetingFlagKey: String? = this.targetingFlagKey,
        internalTargetingFlagKey: String? = this.internalTargetingFlagKey,
        conditions: SurveyConditions? = this.conditions,
        appearance: SurveyAppearance? = this.appearance,
        currentIteration: Int? = this.currentIteration,
        currentIterationStartDate: Date? = this.currentIterationStartDate,
        startDate: Date? = this.startDate,
        endDate: Date? = this.endDate,
        schedule: SurveySchedule? = this.schedule,
        translations: Map<String, SurveyTranslation>? = this.translations,
    ): Survey =
        Survey(
            id = id,
            name = name,
            type = type,
            questions = questions,
            description = description,
            featureFlagKeys = featureFlagKeys,
            linkedFlagKey = linkedFlagKey,
            targetingFlagKey = targetingFlagKey,
            internalTargetingFlagKey = internalTargetingFlagKey,
            conditions = conditions,
            appearance = appearance,
            currentIteration = currentIteration,
            currentIterationStartDate = currentIterationStartDate,
            startDate = startDate,
            endDate = endDate,
            schedule = schedule,
            translations = translations,
            enablePartialResponses = enablePartialResponses,
        )
}
