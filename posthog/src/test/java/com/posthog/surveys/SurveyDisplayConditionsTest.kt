package com.posthog.surveys

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SurveyDisplayConditionsTest {
    @Test
    fun `device type matching with exact match type`() {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = listOf("mobile", "tablet"),
                deviceTypesMatchType = SurveyMatchType.EXACT,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForDeviceType(conditions, "mobile"))
        assertTrue(shouldShowForDeviceType(conditions, "tablet"))
        assertFalse(shouldShowForDeviceType(conditions, "desktop"))
        assertFalse(shouldShowForDeviceType(conditions, "other"))
    }

    @Test
    fun `device type matching with is_not match type`() {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = listOf("desktop"),
                deviceTypesMatchType = SurveyMatchType.IS_NOT,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForDeviceType(conditions, "mobile"))
        assertTrue(shouldShowForDeviceType(conditions, "tablet"))
        assertFalse(shouldShowForDeviceType(conditions, "desktop"))
    }

    @Test
    fun `null device types should allow all device types`() {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForDeviceType(conditions, "mobile"))
        assertTrue(shouldShowForDeviceType(conditions, "tablet"))
        assertTrue(shouldShowForDeviceType(conditions, "desktop"))
    }

    @Test
    fun `event condition matching with single event`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )

        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )

        assertTrue(shouldShowForEvent(conditions, "app_opened"))
        assertFalse(shouldShowForEvent(conditions, "button_clicked"))
    }

    @Test
    fun `event condition matching with multiple events`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                        SurveyEventCondition(name = "feature_used"),
                        SurveyEventCondition(name = "settings_viewed"),
                    ),
            )

        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )

        assertTrue(shouldShowForEvent(conditions, "app_opened"))
        assertTrue(shouldShowForEvent(conditions, "feature_used"))
        assertTrue(shouldShowForEvent(conditions, "settings_viewed"))
        assertFalse(shouldShowForEvent(conditions, "button_clicked"))
    }

    @Test
    fun `null events should not match any event`() {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertFalse(shouldShowForEvent(conditions, "app_opened"))
        assertFalse(shouldShowForEvent(conditions, "button_clicked"))
    }

    @Test
    fun `url matching with exact match type`() {
        val conditions =
            SurveyConditions(
                url = "https://example.com/checkout",
                urlMatchType = SurveyMatchType.EXACT,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForUrl(conditions, "https://example.com/checkout"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com/checkout/success"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com"))
    }

    @Test
    fun `url matching with icontains match type`() {
        val conditions =
            SurveyConditions(
                url = "checkout",
                urlMatchType = SurveyMatchType.I_CONTAINS,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForUrl(conditions, "https://example.com/checkout"))
        assertTrue(shouldShowForUrl(conditions, "https://example.com/checkout/success"))
        assertTrue(shouldShowForUrl(conditions, "https://example.com/user/CHECKOUT"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com"))
    }

    @Test
    fun `url matching with not_icontains match type`() {
        val conditions =
            SurveyConditions(
                url = "checkout",
                urlMatchType = SurveyMatchType.NOT_I_CONTAINS,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertFalse(shouldShowForUrl(conditions, "https://example.com/checkout"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com/checkout/success"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com/user/CHECKOUT"))
        assertTrue(shouldShowForUrl(conditions, "https://example.com"))
    }

    @Test
    fun `url matching with regex match type`() {
        val conditions =
            SurveyConditions(
                url = "^https://example\\.com/product/\\d+$",
                urlMatchType = SurveyMatchType.REGEX,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForUrl(conditions, "https://example.com/product/123"))
        assertTrue(shouldShowForUrl(conditions, "https://example.com/product/456"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com/product/abc"))
        assertFalse(shouldShowForUrl(conditions, "https://example.com/products"))
    }

    @Test
    fun `null url should match any url`() {
        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = null,
                deviceTypesMatchType = null,
                seenSurveyWaitPeriodInDays = null,
                events = null,
            )

        assertTrue(shouldShowForUrl(conditions, "https://example.com/checkout"))
        assertTrue(shouldShowForUrl(conditions, "https://example.com"))
        assertTrue(shouldShowForUrl(conditions, "any-url"))
    }

    @Test
    fun `combined conditions with device type and event`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "app_opened"),
                    ),
            )

        val conditions =
            SurveyConditions(
                url = null,
                urlMatchType = null,
                selector = null,
                deviceTypes = listOf("mobile"),
                deviceTypesMatchType = SurveyMatchType.EXACT,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )

        assertTrue(shouldShowSurvey(conditions, "mobile", "app_opened", "any-url"))
        assertFalse(shouldShowSurvey(conditions, "desktop", "app_opened", "any-url"))
        assertFalse(shouldShowSurvey(conditions, "mobile", "button_clicked", "any-url"))
        assertFalse(shouldShowSurvey(conditions, "desktop", "button_clicked", "any-url"))
    }

    @Test
    fun `combined conditions with device type, event, and url`() {
        val eventConditions =
            SurveyEventConditions(
                repeatedActivation = false,
                values =
                    listOf(
                        SurveyEventCondition(name = "checkout_started"),
                    ),
            )

        val conditions =
            SurveyConditions(
                url = "checkout",
                urlMatchType = SurveyMatchType.I_CONTAINS,
                selector = null,
                deviceTypes = listOf("mobile", "tablet"),
                deviceTypesMatchType = SurveyMatchType.EXACT,
                seenSurveyWaitPeriodInDays = null,
                events = eventConditions,
            )

        assertTrue(shouldShowSurvey(conditions, "mobile", "checkout_started", "https://example.com/checkout"))
        assertTrue(shouldShowSurvey(conditions, "tablet", "checkout_started", "https://example.com/checkout"))
        assertFalse(shouldShowSurvey(conditions, "desktop", "checkout_started", "https://example.com/checkout"))
        assertFalse(shouldShowSurvey(conditions, "mobile", "other_event", "https://example.com/checkout"))
        assertFalse(shouldShowSurvey(conditions, "mobile", "checkout_started", "https://example.com/home"))
    }

    // Helper methods to simulate condition matching logic
    private fun shouldShowForDeviceType(
        conditions: SurveyConditions,
        deviceType: String,
    ): Boolean {
        val deviceTypes = conditions.deviceTypes ?: return true

        return when (conditions.deviceTypesMatchType) {
            SurveyMatchType.EXACT -> deviceTypes.contains(deviceType)
            SurveyMatchType.IS_NOT -> !deviceTypes.contains(deviceType)
            else -> true // Default to showing if match type is not handled
        }
    }

    private fun shouldShowForEvent(
        conditions: SurveyConditions,
        eventName: String,
    ): Boolean {
        val events = conditions.events ?: return false

        return events.values.any { it.name == eventName }
    }

    private fun shouldShowForUrl(
        conditions: SurveyConditions,
        url: String,
    ): Boolean {
        val conditionUrl = conditions.url ?: return true

        return when (conditions.urlMatchType) {
            SurveyMatchType.EXACT -> conditionUrl == url
            SurveyMatchType.IS_NOT -> conditionUrl != url
            SurveyMatchType.I_CONTAINS -> url.lowercase().contains(conditionUrl.lowercase())
            SurveyMatchType.NOT_I_CONTAINS -> !url.lowercase().contains(conditionUrl.lowercase())
            SurveyMatchType.REGEX -> url.matches(Regex(conditionUrl))
            SurveyMatchType.NOT_REGEX -> !url.matches(Regex(conditionUrl))
            else -> true // Default to showing if match type is not handled
        }
    }

    private fun shouldShowSurvey(
        conditions: SurveyConditions,
        deviceType: String,
        eventName: String,
        url: String,
    ): Boolean {
        return shouldShowForDeviceType(conditions, deviceType) &&
            shouldShowForEvent(conditions, eventName) &&
            shouldShowForUrl(conditions, url)
    }
}
