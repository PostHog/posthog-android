package com.posthog.surveys

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * A survey configuration as returned by PostHog, describing its questions, targeting,
 * appearance, and scheduling.
 */
public data class Survey(
    /** The unique identifier of the survey. */
    val id: String,
    /** The name of the survey. */
    val name: String,
    /** How the survey is presented to the user. */
    val type: SurveyType,
    /** The ordered list of questions the survey asks. */
    val questions: List<SurveyQuestion>,
    /** An internal description of the survey; not shown to end users. */
    val description: String?,
    /** Feature flags the survey is linked to; the survey shows only when they are enabled. */
    @SerializedName("feature_flag_keys")
    val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?,
    /** The key of a feature flag the survey is linked to; the survey shows only for users the flag is enabled for. */
    @SerializedName("linked_flag_key")
    val linkedFlagKey: String?,
    /** The key of the feature flag used to target which users are eligible for the survey. */
    @SerializedName("targeting_flag_key")
    val targetingFlagKey: String?,
    /** The key of an internally generated flag used to target users who have not yet seen the survey. */
    @SerializedName("internal_targeting_flag_key")
    val internalTargetingFlagKey: String?,
    /** The targeting and display conditions that determine when the survey is shown. */
    val conditions: SurveyConditions?,
    /** The appearance and styling configuration for the survey UI. */
    val appearance: SurveyAppearance?,
    /** The current iteration number, for recurring surveys. */
    @SerializedName("current_iteration")
    val currentIteration: Int?,
    /** The date the current iteration started, for recurring surveys. */
    @SerializedName("current_iteration_start_date")
    val currentIterationStartDate: Date?,
    /** The date the survey started being shown. */
    @SerializedName("start_date")
    val startDate: Date?,
    /** The date the survey stopped being shown, if it has been stopped. */
    @SerializedName("end_date")
    val endDate: Date?,
    /** How often the survey may be shown. */
    val schedule: SurveySchedule?,
    /** Localized survey-level overrides, keyed by language code (for example "fr", "pt-BR"). */
    val translations: Map<String, SurveyTranslation>? = null,
)
