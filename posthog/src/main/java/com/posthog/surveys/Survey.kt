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
)
