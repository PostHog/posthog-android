package com.posthog.surveys

public data class SurveyConditions
    @JvmOverloads
    public constructor(
        val url: String?,
        val urlMatchType: SurveyMatchType?,
        val selector: String?,
        val deviceTypes: List<String>?,
        val deviceTypesMatchType: SurveyMatchType?,
        val seenSurveyWaitPeriodInDays: Int?,
        val events: SurveyEventConditions?,
        /** Required variant of the linked flag; "any" accepts any enabled value. */
        val linkedFlagVariant: String? = null,
        // TODO: actions
    ) {
        // Preserve the original copy/copy$default signatures for compiled SDK consumers.
        public fun copy(
            url: String? = this.url,
            urlMatchType: SurveyMatchType? = this.urlMatchType,
            selector: String? = this.selector,
            deviceTypes: List<String>? = this.deviceTypes,
            deviceTypesMatchType: SurveyMatchType? = this.deviceTypesMatchType,
            seenSurveyWaitPeriodInDays: Int? = this.seenSurveyWaitPeriodInDays,
            events: SurveyEventConditions? = this.events,
        ): SurveyConditions =
            copy(
                url,
                urlMatchType,
                selector,
                deviceTypes,
                deviceTypesMatchType,
                seenSurveyWaitPeriodInDays,
                events,
                linkedFlagVariant,
            )
    }
