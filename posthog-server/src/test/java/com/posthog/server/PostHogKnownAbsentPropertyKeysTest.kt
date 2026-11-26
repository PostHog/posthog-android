package com.posthog.server

import com.google.gson.Gson
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class PostHogKnownAbsentPropertyKeysTest {
    private class FlagsBodyDispatcher(
        private val definitions: String,
        private val flagsResponse: String,
    ) : Dispatcher() {
        val flagsBodies: MutableList<Map<String, Any?>> = CopyOnWriteArrayList()
        val featureFlagCalledEvents: MutableList<Map<String, Any?>> = CopyOnWriteArrayList()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("local_evaluation") -> jsonResponse(definitions)
                path.contains("/flags") -> {
                    @Suppress("UNCHECKED_CAST")
                    flagsBodies.add(Gson().fromJson(request.body.unGzip(), Map::class.java) as Map<String, Any?>)
                    jsonResponse(flagsResponse)
                }
                path.contains("/batch") -> {
                    request.parseBatch().batch
                        .filter { it.get("event")?.asString == "\$feature_flag_called" }
                        .forEach {
                            @Suppress("UNCHECKED_CAST")
                            featureFlagCalledEvents.add(
                                Gson().fromJson(it.getAsJsonObject("properties"), Map::class.java) as Map<String, Any?>,
                            )
                        }
                    MockResponse().setResponseCode(200)
                }
                else -> MockResponse().setResponseCode(200)
            }
        }
    }

    private fun withPostHog(
        definitions: String,
        flagsResponse: String = createEmptyFlagsResponse(),
        sendFeatureFlagEvent: Boolean = false,
        block: (PostHogInterface, FlagsBodyDispatcher) -> Unit,
    ) {
        val dispatcher = FlagsBodyDispatcher(definitions, flagsResponse)
        val mockServer = MockWebServer()
        mockServer.dispatcher = dispatcher
        mockServer.start()
        val postHog =
            PostHog.with(
                PostHogConfig.builder(TEST_PROJECT_TOKEN)
                    .host(mockServer.url("/").toString())
                    .personalApiKey("phx_test_personal_api_key")
                    .sendFeatureFlagEvent(sendFeatureFlagEvent)
                    .flushAt(1)
                    .build(),
            )
        try {
            block(postHog, dispatcher)
        } finally {
            postHog.close()
            mockServer.shutdown()
        }
    }

    private fun personFlag(
        key: String,
        operator: String,
        propertyKey: String = "plan",
        valueField: String = ", \"value\": \"pro\"",
    ): String =
        """
        {"id": 1, "key": "$key", "name": "$key", "active": true, "version": 1,
         "filters": {"groups": [{"properties": [
           {"key": "$propertyKey", "type": "person", "operator": "$operator"$valueField}
         ], "rollout_percentage": 100}]}}
        """.trimIndent()

    private fun groupDefinitions(): String =
        """
        {"flags": [
          {"id": 1, "key": "org-incomplete", "name": "org-incomplete", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
             {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
           ], "rollout_percentage": 100}]}}
         ],
         "group_type_mapping": {"0": "organization", "1": "project"},
         "cohorts": {}}
        """.trimIndent()

    @Test
    fun `a listed key matches is_not_set and overrides a supplied value without a flags request`() {
        withPostHog(createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set"))) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("plan", "pro")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("profile-incomplete"))

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertEquals(true, postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertTrue(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a listed key wins over the distinct_id the SDK adds`() {
        withPostHog(createLocalEvaluationResponseFrom(personFlag("no-distinct-id", "is_not_set", "distinct_id"))) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("distinct_id"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("no-distinct-id"))
            assertFalse(postHog.evaluateFlags("user-1").isEnabled("no-distinct-id"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group key applies only to its group type`() {
        withPostHog(groupDefinitions(), createMultipleFlagsResponse("org-incomplete" to false)) { postHog, dispatcher ->
            val listed =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", listed).isEnabled("org-incomplete"))
            assertEquals(0, dispatcher.flagsBodies.size)

            val otherType =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("project", listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", otherType).isEnabled("org-incomplete"))
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(mapOf("project" to listOf("tier")), dispatcher.flagsBodies[0]["group_property_keys_known_absent"])
        }
    }

    @Test
    fun `remote fallback sends the listed keys and omits them when none were provided`() {
        val definitions =
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set"), emailGatedFlagDefinition("gated"))
        withPostHog(definitions, createMultipleFlagsResponse("gated" to true)) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan", "plan", "__posthog_person_metadata__created_at"))
                    .groupPropertyKeysKnownAbsent(mapOf("organization" to listOf("tier"), "project" to emptyList()))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertTrue(snapshot.isEnabled("profile-incomplete"))
            assertTrue(snapshot.isEnabled("gated"))

            postHog.evaluateFlags("user-2")
            postHog.evaluateFlags("user-3", PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(emptyList()).build())

            assertEquals(3, dispatcher.flagsBodies.size)
            assertEquals(
                listOf("plan", "__posthog_person_metadata__created_at"),
                dispatcher.flagsBodies[0]["person_property_keys_known_absent"],
            )
            assertEquals(mapOf("organization" to listOf("tier")), dispatcher.flagsBodies[0]["group_property_keys_known_absent"])
            for (body in dispatcher.flagsBodies.drop(1)) {
                assertFalse(body.containsKey("person_property_keys_known_absent"))
                assertFalse(body.containsKey("group_property_keys_known_absent"))
            }
        }
    }

    @Test
    fun `flag getters send the listed keys on fallback and cache them separately`() {
        withPostHog(createLocalEvaluationResponseFrom(emailGatedFlagDefinition("gated")), createMultipleFlagsResponse("gated" to true)) {
                postHog,
                dispatcher,
            ->
            val options =
                PostHogFeatureFlagResultOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertEquals(true, postHog.getFeatureFlagResult("user-1", "gated", options)?.value)
            assertEquals(true, postHog.getFeatureFlag("user-1", "gated"))

            assertEquals(2, dispatcher.flagsBodies.size, "the listing is part of the cache key")
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
            assertFalse(dispatcher.flagsBodies[1].containsKey("person_property_keys_known_absent"))
        }
    }

    @Test
    fun `flag getters with listed keys send feature_flag_called from the known-absent cache entry`() {
        val remote =
            """
            {"flags": {
              "gated": {"key": "gated", "enabled": true, "variant": null,
                "metadata": {"id": 7, "version": 3, "payload": "pay"},
                "reason": {"code": "condition_match", "description": "Matched", "condition_index": 0}},
              "quiet": {"key": "quiet", "enabled": true, "variant": null, "metadata": {"id": 8, "version": 1, "payload": null}},
              "broken": {"key": "broken", "enabled": false, "failed": true, "metadata": {"id": 9, "version": 1, "payload": null}}
             },
             "requestId": "req-known-absent"}
            """.trimIndent()
        val definitions =
            createLocalEvaluationResponseFrom(
                emailGatedFlagDefinition("gated"),
                emailGatedFlagDefinition("quiet"),
                emailGatedFlagDefinition("broken"),
            )
        withPostHog(definitions, remote, sendFeatureFlagEvent = true) { postHog, dispatcher ->
            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .defaultValue("fallback")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertEquals("pay", postHog.getFeatureFlagPayload("user-1", "gated", flagOptions))
            val quiet =
                PostHogFeatureFlagResultOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .sendFeatureFlagEvent(false)
                    .build()
            assertEquals(true, postHog.getFeatureFlagResult("user-1", "quiet", quiet)?.value)
            assertEquals(true, postHog.getFeatureFlag("user-1", "gated", flagOptions))
            assertEquals("fallback", postHog.getFeatureFlag("user-1", "broken", flagOptions))
            postHog.flush()

            val deadline = System.currentTimeMillis() + 5_000
            while (dispatcher.featureFlagCalledEvents.size < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val events = dispatcher.featureFlagCalledEvents.associateBy { it["\$feature_flag"] }
            assertEquals(setOf<Any?>("gated", "broken"), events.keys)
            val gated = events.getValue("gated")
            assertEquals(true, gated["\$feature_flag_response"])
            assertEquals("req-known-absent", gated["\$feature_flag_request_id"])
            assertEquals(7.0, gated["\$feature_flag_id"])
            assertEquals(3.0, gated["\$feature_flag_version"])
            assertEquals("Matched", gated["\$feature_flag_reason"])
            val broken = events.getValue("broken")
            assertEquals("", broken["\$feature_flag_response"])
            assertEquals(9.0, broken["\$feature_flag_id"])
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
        }
    }

    @Test
    fun `an unsupported operator on a listed key still falls back to flags`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("multi", "not_icontains_multi")),
            createMultipleFlagsResponse("multi" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", options).isEnabled("multi"))
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
        }
    }

    @Test
    fun `an unsupported operator in a cohort leaf on a listed key falls back before known absence`() {
        for (negation in listOf(false, true)) {
            val definitions =
                """
                {"flags": [
                  {"id": 1, "key": "cohort-range", "name": "cohort-range", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [
                     {"key": "id", "type": "cohort", "value": 1}
                   ], "rollout_percentage": 100}]}}
                 ],
                 "group_type_mapping": {},
                 "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [
                   {"key": "plan", "type": "person", "operator": "not_between", "value": [1, 2], "negation": $negation}
                 ]}]}}}
                """.trimIndent()
            withPostHog(definitions, createMultipleFlagsResponse("cohort-range" to true)) { postHog, dispatcher ->
                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(true, postHog.getFeatureFlag("user-1", "cohort-range", options), "negation=$negation")
                assertEquals(1, dispatcher.flagsBodies.size, "negation=$negation")
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
            }
        }
    }

    @Test
    fun `an omitted or null condition value on a listed key stays inconclusive and falls back to flags`() {
        val cases =
            listOf(
                "is_not" to "",
                "is_not" to ", \"value\": null",
                "exact" to ", \"value\": null",
                "gt" to ", \"value\": null",
            )
        for ((operator, valueField) in cases) {
            val label = "$operator with '$valueField'"
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("no-value", operator, valueField = valueField)),
                createMultipleFlagsResponse("no-value" to false),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personProperty("plan", "pro")
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertEquals(mapOf("no-value" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION), snapshot.unresolvedFlags, label)
                assertEquals(0, dispatcher.flagsBodies.size, label)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(false, postHog.getFeatureFlag("user-1", "no-value", options), label)
                assertEquals(1, dispatcher.flagsBodies.size, label)
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], label)
            }
        }
    }

    @Test
    fun `an omitted or null condition value on a listed key stays inconclusive whether or not the key is supplied`() {
        for (operator in listOf("exact", "is_not", "icontains", "gt", "is_date_before")) {
            for (valueField in listOf("", ", \"value\": null")) {
                for (supplied in listOf(false, true)) {
                    val label = "$operator with '$valueField', supplied=$supplied"
                    withPostHog(
                        createLocalEvaluationResponseFrom(personFlag("no-value", operator, valueField = valueField)),
                        createMultipleFlagsResponse("no-value" to true),
                    ) { postHog, dispatcher ->
                        val localOnly = PostHogEvaluateFlagsOptions.builder().onlyEvaluateLocally(true)
                        if (supplied) localOnly.personProperty("plan", "pro")
                        val snapshot = postHog.evaluateFlags("user-1", localOnly.personPropertyKeysKnownAbsent(listOf("plan")).build())
                        assertEquals(
                            mapOf("no-value" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION),
                            snapshot.unresolvedFlags,
                            label,
                        )
                        assertEquals(0, dispatcher.flagsBodies.size, label)

                        val flagOptions = PostHogFeatureFlagOptions.builder()
                        if (supplied) flagOptions.personProperty("plan", "pro")
                        val options = flagOptions.personPropertyKeysKnownAbsent(listOf("plan")).build()
                        assertEquals(true, postHog.getFeatureFlag("user-1", "no-value", options), label)
                        assertEquals(1, dispatcher.flagsBodies.size, label)
                        assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], label)
                    }
                }
            }
        }
    }

    @Test
    fun `is_set and is_not_set with an omitted or null condition value on a listed key resolve locally`() {
        for (valueField in listOf("", ", \"value\": null")) {
            for (supplied in listOf(false, true)) {
                val label = "'$valueField', supplied=$supplied"
                val definitions =
                    createLocalEvaluationResponseFrom(
                        personFlag("not-set", "is_not_set", valueField = valueField),
                        personFlag("set", "is_set", valueField = valueField).replace("\"id\": 1", "\"id\": 2"),
                    )
                withPostHog(definitions) { postHog, dispatcher ->
                    val builder = PostHogEvaluateFlagsOptions.builder()
                    if (supplied) builder.personProperty("plan", "pro")
                    val snapshot = postHog.evaluateFlags("user-1", builder.personPropertyKeysKnownAbsent(listOf("plan")).build())
                    assertTrue(snapshot.isEnabled("not-set"), label)
                    assertFalse(snapshot.isEnabled("set"), label)
                    assertTrue(snapshot.unresolvedFlags.isEmpty(), label)
                    assertEquals(0, dispatcher.flagsBodies.size, label)
                }
            }
        }
    }

    @Test
    fun `a present condition value on a listed key resolves locally whether or not the key is supplied`() {
        val cases =
            listOf(
                Triple("exact", "\"pro\"", false),
                Triple("is_not", "\"pro\"", true),
                Triple("icontains", "\"pro\"", false),
                Triple("gt", "5", false),
                Triple("is_date_before", "\"2020-01-01\"", false),
            )
        for ((operator, value, expected) in cases) {
            for (supplied in listOf(false, true)) {
                val label = "$operator, supplied=$supplied"
                withPostHog(
                    createLocalEvaluationResponseFrom(personFlag("valued", operator, valueField = ", \"value\": $value")),
                    createMultipleFlagsResponse("valued" to !expected),
                ) { postHog, dispatcher ->
                    val builder = PostHogEvaluateFlagsOptions.builder()
                    if (supplied) builder.personProperty("plan", if (operator == "gt") 10 else "pro")
                    val snapshot = postHog.evaluateFlags("user-1", builder.personPropertyKeysKnownAbsent(listOf("plan")).build())
                    assertTrue(snapshot.unresolvedFlags.isEmpty(), label)
                    assertEquals(expected, snapshot.isEnabled("valued"), label)
                    assertEquals(0, dispatcher.flagsBodies.size, label)
                }
            }
        }
    }

    @Test
    fun `an omitted or null condition value on a supplied key that is not listed keeps its existing result`() {
        val unsupported = mapOf("no-value" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION)
        val cases =
            listOf(
                Triple("exact", false, emptyMap()),
                Triple("is_not", true, emptyMap()),
                Triple("icontains", false, emptyMap()),
                Triple("gt", false, unsupported),
                Triple("is_date_before", false, unsupported),
            )
        for ((operator, expected, expectedUnresolved) in cases) {
            for (valueField in listOf("", ", \"value\": null")) {
                val label = "$operator with '$valueField'"
                withPostHog(
                    createLocalEvaluationResponseFrom(personFlag("no-value", operator, valueField = valueField)),
                    createMultipleFlagsResponse("no-value" to true),
                ) { postHog, dispatcher ->
                    val options =
                        PostHogEvaluateFlagsOptions.builder()
                            .personProperty("plan", "pro")
                            .onlyEvaluateLocally(true)
                            .build()
                    val snapshot = postHog.evaluateFlags("user-1", options)
                    assertEquals(expected, snapshot.isEnabled("no-value"), label)
                    assertEquals<Map<String, PostHogUnresolvedFlagReason>>(expectedUnresolved, snapshot.unresolvedFlags, label)
                    assertEquals(0, dispatcher.flagsBodies.size, label)
                }
            }
        }
    }

    @Test
    fun `a listed key resolves without reading a present condition value`() {
        val cases =
            listOf(
                Triple("not_regex", "\"[\"", true),
                Triple("regex", "\"[\"", false),
                Triple("not_regex", "\"\\\\p{L}\"", true),
                Triple("regex", "\"\\\\p{L}\"", false),
                Triple("gt", "\"not-a-number\"", false),
                Triple("gte", "\"not-a-number\"", false),
                Triple("lt", "\"not-a-number\"", false),
                Triple("lte", "\"not-a-number\"", false),
                Triple("semver_gt", "\"not-a-version\"", false),
                Triple("semver_eq", "\"not-a-version\"", false),
                Triple("semver_tilde", "\"not-a-version\"", false),
                Triple("semver_caret", "\"not-a-version\"", false),
                Triple("semver_wildcard", "\"not-a-version\"", false),
                Triple("is_date_before", "\"not-a-date\"", false),
                Triple("is_date_after", "\"not-a-date\"", false),
            )
        for ((operator, value, expected) in cases) {
            val label = "$operator with $value"
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("malformed", operator, valueField = ", \"value\": $value")),
                createMultipleFlagsResponse("malformed" to !expected),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personProperty("plan", "pro")
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertTrue(snapshot.unresolvedFlags.isEmpty(), label)
                assertEquals(expected, snapshot.isEnabled("malformed"), label)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(expected, postHog.getFeatureFlag("user-1", "malformed", options), label)
                assertEquals(0, dispatcher.flagsBodies.size, label)
            }
        }
    }

    @Test
    fun `an operator the SDK does not implement on a listed key stays unsupported and falls back to flags`() {
        val cases =
            listOf(
                "icontains_multi" to "[\"pro\"]",
                "not_icontains_multi" to "[\"pro\"]",
                "between" to "[1, 2]",
                "not_between" to "[1, 2]",
                "future_operator" to "\"pro\"",
            )
        for ((operator, value) in cases) {
            withPostHog(
                createLocalEvaluationResponseFrom(personFlag("unsupported", operator, valueField = ", \"value\": $value")),
                createMultipleFlagsResponse("unsupported" to true),
            ) { postHog, dispatcher ->
                val localOnly =
                    PostHogEvaluateFlagsOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .onlyEvaluateLocally(true)
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", localOnly)
                assertEquals(mapOf("unsupported" to PostHogUnresolvedFlagReason.UNSUPPORTED_DEFINITION), snapshot.unresolvedFlags, operator)
                assertEquals(0, dispatcher.flagsBodies.size, operator)

                val options =
                    PostHogFeatureFlagOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("plan"))
                        .build()
                assertEquals(true, postHog.getFeatureFlag("user-1", "unsupported", options), operator)
                assertEquals(1, dispatcher.flagsBodies.size, operator)
                assertEquals(listOf("plan"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], operator)
            }
        }
    }

    @Test
    fun `is_set and is_not_set with a null condition value on a listed key keep their known-absent result`() {
        val definitions =
            createLocalEvaluationResponseFrom(
                personFlag("not-set", "is_not_set", valueField = ", \"value\": null"),
                personFlag("set", "is_set", valueField = ", \"value\": null").replace("\"id\": 1", "\"id\": 2"),
            )
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("plan", "pro")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertTrue(snapshot.isEnabled("not-set"))
            assertFalse(snapshot.isEnabled("set"))
            assertTrue(snapshot.unresolvedFlags.isEmpty())
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a listed person_metadata key never resolves from the listing`() {
        val cohortLeaf = { operator: String, value: String ->
            """{"key": "created_at", "type": "person_metadata", "operator": "$operator"$value}"""
        }
        for ((operator, value) in listOf("is_not_set" to "", "is_date_before" to ", \"value\": \"2020-01-01\"")) {
            val definitions =
                """
                {"flags": [
                  {"id": 1, "key": "new-user-only", "name": "new-user-only", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [${cohortLeaf(operator, value)}], "rollout_percentage": 100}]}},
                  {"id": 2, "key": "new-user-cohort", "name": "new-user-cohort", "active": true, "version": 1,
                   "filters": {"groups": [{"properties": [{"key": "id", "type": "cohort", "value": 1}], "rollout_percentage": 100}]}}
                 ],
                 "group_type_mapping": {},
                 "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [${cohortLeaf(operator, value)}]}]}}}
                """.trimIndent()
            withPostHog(
                definitions,
                createMultipleFlagsResponse("new-user-only" to false, "new-user-cohort" to false),
            ) { postHog, dispatcher ->
                val options =
                    PostHogEvaluateFlagsOptions.builder()
                        .personPropertyKeysKnownAbsent(listOf("created_at"))
                        .build()
                val snapshot = postHog.evaluateFlags("user-1", options)
                assertFalse(snapshot.isEnabled("new-user-only"), operator)
                assertFalse(snapshot.isEnabled("new-user-cohort"), operator)
                assertEquals(1, dispatcher.flagsBodies.size, operator)
                assertEquals(listOf("created_at"), dispatcher.flagsBodies[0]["person_property_keys_known_absent"], operator)
            }
        }
    }

    @Test
    fun `a listed person-metadata prefixed key is forwarded unchanged but never resolves a person filter locally`() {
        val metadataKey = "__posthog_person_metadata__created_at"
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("metadata-unset", "is_not_set", metadataKey, valueField = "")),
            createMultipleFlagsResponse("metadata-unset" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personPropertyKeysKnownAbsent(listOf(metadataKey))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", options).isEnabled("metadata-unset"))
            assertEquals(1, dispatcher.flagsBodies.size)
            assertEquals(listOf(metadataKey), dispatcher.flagsBodies[0]["person_property_keys_known_absent"])
        }
    }

    @Test
    fun `a person listing does not resolve a group filter`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-incomplete", "name": "org-incomplete", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "person-flag-group-filter", "name": "person-flag-group-filter", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(
            definitions,
            createMultipleFlagsResponse("org-incomplete" to false, "person-flag-group-filter" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .personPropertyKeysKnownAbsent(listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-incomplete"))
            assertFalse(snapshot.isEnabled("person-flag-group-filter"))
            assertEquals(1, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group listing does not resolve a person filter or person cohort leaf on a group flag`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-person-filter", "name": "org-person-filter", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "person", "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "org-cohort-flag", "name": "org-cohort-flag", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "id", "type": "cohort", "value": 1}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {"1": {"type": "OR", "values": [{"type": "AND", "values": [
               {"key": "tier", "type": "person", "operator": "is_not_set"}
             ]}]}}}
            """.trimIndent()
        withPostHog(
            definitions,
            createMultipleFlagsResponse("org-person-filter" to false, "org-cohort-flag" to false),
        ) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-person-filter"))
            assertFalse(snapshot.isEnabled("org-cohort-flag"))
            assertEquals(1, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a flag dependency with a different aggregation uses the listing for its own aggregation`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "group-dep", "name": "group-dep", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "person-parent", "name": "person-parent", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "group-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["group-dep"]}
               ], "rollout_percentage": 100}]}},
              {"id": 3, "key": "person-dep", "name": "person-dep", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "tier", "type": "person", "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 4, "key": "group-parent", "name": "group-parent", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "person-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["person-dep"]}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        val remote = createMultipleFlagsResponse("person-parent" to false, "group-parent" to false)
        withPostHog(definitions, remote) { postHog, dispatcher ->
            val personListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("person-parent"))
                    .group("organization", "org-1")
                    .personPropertyKeysKnownAbsent(listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", personListing).isEnabled("person-parent"))
            assertEquals(1, dispatcher.flagsBodies.size)

            val groupListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("group-parent"))
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", groupListing).isEnabled("group-parent"))
            assertEquals(2, dispatcher.flagsBodies.size)

            val dependencyGroupListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("person-parent"))
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", dependencyGroupListing).isEnabled("person-parent"))

            val dependencyPersonListing =
                PostHogEvaluateFlagsOptions.builder()
                    .flagKeys(listOf("group-parent"))
                    .group("organization", "org-1")
                    .personPropertyKeysKnownAbsent(listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", dependencyPersonListing).isEnabled("group-parent"))
            assertEquals(2, dispatcher.flagsBodies.size)
        }
    }

    private fun crossAggregationDefinitions(
        orgParentFilter: String,
        orgParentRollout: Int = 100,
    ): String =
        """
        {"flags": [
          {"id": 1, "key": "org-parent", "name": "org-parent", "active": true, "version": 1,
           "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [$orgParentFilter],
             "rollout_percentage": $orgParentRollout}]}},
          {"id": 2, "key": "person-child", "name": "person-child", "active": true, "version": 1,
           "filters": {"groups": [{"properties": [
             {"key": "org-parent", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["org-parent"]}
           ], "rollout_percentage": 100}]}}
         ],
         "group_type_mapping": {"0": "organization"},
         "cohorts": {}}
        """.trimIndent()

    @Test
    fun `a group dependency reads its group's properties, not the person's`() {
        val definitions =
            crossAggregationDefinitions(
                """{"key": "region", "type": "group", "group_type_index": 0, "operator": "exact", "value": ["us"]}""",
            )
        withPostHog(definitions, createMultipleFlagsResponse("org-parent" to true, "person-child" to true)) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("region", "us")
                    .group("organization", "org-1")
                    .groupProperty("organization", "region", "eu")
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-parent"))
            assertFalse(snapshot.isEnabled("person-child"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group dependency without its group is false and does not fall back`() {
        val definitions =
            crossAggregationDefinitions("""{"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}""")
        withPostHog(definitions, createMultipleFlagsResponse("org-parent" to true, "person-child" to true)) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-parent"))
            assertFalse(snapshot.isEnabled("person-child"))
            assertFalse(postHog.evaluateFlags("user-1").isEnabled("person-child"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group dependency is bucketed on its group key`() {
        withPostHog(crossAggregationDefinitions("", orgParentRollout = 50)) { postHog, dispatcher ->
            val parentValues =
                (1..40).map { index ->
                    val options = PostHogEvaluateFlagsOptions.builder().group("organization", "org-$index").build()
                    val snapshot = postHog.evaluateFlags("user-1", options)
                    assertEquals(snapshot.isEnabled("org-parent"), snapshot.isEnabled("person-child"), "org-$index")
                    snapshot.isEnabled("org-parent")
                }
            assertEquals(setOf(true, false), parentValues.toSet())
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a person dependency of a group flag reads the person's properties and distinct id`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "person-parent", "name": "person-parent", "active": true, "version": 1,
               "filters": {"groups": [{"properties": [
                 {"key": "region", "type": "person", "operator": "exact", "value": ["us"]}
               ], "rollout_percentage": 50}]}},
              {"id": 2, "key": "org-child", "name": "org-child", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "person-parent", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["person-parent"]}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(definitions) { postHog, dispatcher ->
            val parentValues =
                (1..40).map { index ->
                    val options =
                        PostHogEvaluateFlagsOptions.builder()
                            .personProperty("region", "us")
                            .group("organization", "org-1")
                            .groupProperty("organization", "region", "eu")
                            .build()
                    val snapshot = postHog.evaluateFlags("user-$index", options)
                    assertEquals(snapshot.isEnabled("person-parent"), snapshot.isEnabled("org-child"), "user-$index")
                    snapshot.isEnabled("person-parent")
                }
            assertEquals(setOf(true, false), parentValues.toSet())
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group filter without an index reads the flag's group type and its listing`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-enterprise", "name": "org-enterprise", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "operator": "exact", "value": "enterprise"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "org-no-tier", "name": "org-no-tier", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupProperty("organization", "tier", "enterprise")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            val snapshot = postHog.evaluateFlags("user-1", options)
            assertFalse(snapshot.isEnabled("org-enterprise"))
            assertTrue(snapshot.isEnabled("org-no-tier"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a group listing does not resolve a group filter for a different group_type_index`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-with-project-filter", "name": "org-with-project-filter", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 1, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization", "1": "project"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(definitions, createMultipleFlagsResponse("org-with-project-filter" to false)) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .group("project", "proj-1")
                    .groupProperty("project", "tier", "enterprise")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", options).isEnabled("org-with-project-filter"))
            assertEquals(1, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a person listing resolves a person filter that carries a group_type_index`() {
        val definitions =
            createLocalEvaluationResponseFrom(
                """
                {"id": 1, "key": "pro-only", "name": "pro-only", "active": true, "version": 1,
                 "filters": {"groups": [{"properties": [
                   {"key": "plan", "type": "person", "group_type_index": 1, "operator": "exact", "value": "pro"}
                 ], "rollout_percentage": 100}]}}
                """.trimIndent(),
            )
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .personProperty("plan", "pro")
                    .personPropertyKeysKnownAbsent(listOf("plan"))
                    .build()
            assertFalse(postHog.evaluateFlags("user-1", options).isEnabled("pro-only"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a flag dependency with the same aggregation uses the listing`() {
        val definitions =
            """
            {"flags": [
              {"id": 1, "key": "org-dep", "name": "org-dep", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "tier", "type": "group", "group_type_index": 0, "operator": "is_not_set"}
               ], "rollout_percentage": 100}]}},
              {"id": 2, "key": "org-parent", "name": "org-parent", "active": true, "version": 1,
               "filters": {"aggregation_group_type_index": 0, "groups": [{"properties": [
                 {"key": "org-dep", "type": "flag", "operator": "flag_evaluates_to", "value": true, "dependency_chain": ["org-dep"]}
               ], "rollout_percentage": 100}]}}
             ],
             "group_type_mapping": {"0": "organization"},
             "cohorts": {}}
            """.trimIndent()
        withPostHog(definitions) { postHog, dispatcher ->
            val options =
                PostHogEvaluateFlagsOptions.builder()
                    .group("organization", "org-1")
                    .groupPropertyKeysKnownAbsent("organization", listOf("tier"))
                    .build()
            assertTrue(postHog.evaluateFlags("user-1", options).isEnabled("org-parent"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a listed enrollment key means not enrolled and keeps ordinary targeting`() {
        val enrollmentKey = "\$feature_enrollment/beta-feature"

        fun betaFeature(rollout: Int): String =
            """
            {"id": 1, "key": "beta-feature", "name": "beta-feature", "active": true, "version": 1,
             "filters": {"feature_enrollment": true, "groups": [{"properties": [], "rollout_percentage": $rollout}]}}
            """.trimIndent()

        fun options(
            enrollment: Boolean?,
            listed: Boolean,
        ): PostHogEvaluateFlagsOptions {
            val builder = PostHogEvaluateFlagsOptions.builder().onlyEvaluateLocally(true)
            if (enrollment != null) builder.personProperty(enrollmentKey, enrollment)
            if (listed) builder.personPropertyKeysKnownAbsent(listOf(enrollmentKey))
            return builder.build()
        }

        // The listing wins over a supplied enrollment, so a 0% rollout leaves the person out.
        withPostHog(createLocalEvaluationResponseFrom(betaFeature(rollout = 0))) { postHog, dispatcher ->
            val snapshot = postHog.evaluateFlags("user-1", options(enrollment = true, listed = true))
            assertFalse(snapshot.isEnabled("beta-feature"))
            assertTrue(snapshot.unresolvedFlags.isEmpty())
            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .personProperty(enrollmentKey, true)
                    .personPropertyKeysKnownAbsent(listOf(enrollmentKey))
                    .build()
            assertEquals(false, postHog.getFeatureFlag("user-1", "beta-feature", flagOptions))

            // Supplied and not listed: enrolled, as before.
            assertTrue(postHog.evaluateFlags("user-1", options(enrollment = true, listed = false)).isEnabled("beta-feature"))
            assertEquals(0, dispatcher.flagsBodies.size)
        }

        // Listed only: the release conditions decide, without missing_context.
        withPostHog(createLocalEvaluationResponseFrom(betaFeature(rollout = 100))) { postHog, dispatcher ->
            val snapshot = postHog.evaluateFlags("user-1", options(enrollment = null, listed = true))
            assertTrue(snapshot.isEnabled("beta-feature"))
            assertTrue(snapshot.unresolvedFlags.isEmpty())
            val flagOptions = PostHogFeatureFlagOptions.builder().personPropertyKeysKnownAbsent(listOf(enrollmentKey)).build()
            assertEquals(true, postHog.getFeatureFlag("user-1", "beta-feature", flagOptions))

            // Neither supplied nor listed: still missing context.
            val unlisted = postHog.evaluateFlags("user-1", options(enrollment = null, listed = false))
            assertEquals(mapOf("beta-feature" to PostHogUnresolvedFlagReason.MISSING_CONTEXT), unlisted.unresolvedFlags)
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `a null key or group type from a Java caller returns the unavailable result without evaluating or sending a request`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set")),
            createMultipleFlagsResponse("profile-incomplete" to true),
        ) { postHog, dispatcher ->
            @Suppress("UNCHECKED_CAST")
            val withNull = java.util.Arrays.asList("plan", null) as List<String>
            val snapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(withNull).build(),
                )
            assertTrue(snapshot.keys.isEmpty())

            @Suppress("UNCHECKED_CAST")
            val groupWithNull = java.util.Arrays.asList("tier", null) as List<String>
            val groupSnapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().groupPropertyKeysKnownAbsent("organization", groupWithNull).build(),
                )
            assertTrue(groupSnapshot.keys.isEmpty())

            @Suppress("UNCHECKED_CAST")
            val nullGroupType =
                java.util.HashMap(
                    java.util.Collections.singletonMap(null, mutableListOf("tier")),
                ) as MutableMap<String, MutableList<String>>
            val nullGroupTypeBuilder = PostHogEvaluateFlagsOptions.builder()
            nullGroupTypeBuilder.groupPropertyKeysKnownAbsent = nullGroupType
            assertTrue(postHog.evaluateFlags("user-1", nullGroupTypeBuilder.build()).keys.isEmpty())

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .defaultValue("fallback")
                    .personPropertyKeysKnownAbsent(withNull)
                    .build()
            assertEquals("fallback", postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertEquals("fallback", postHog.getFeatureFlagPayload("user-1", "profile-incomplete", flagOptions))
            assertFalse(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertNull(
                postHog.getFeatureFlagResult(
                    "user-1",
                    "profile-incomplete",
                    PostHogFeatureFlagResultOptions.builder().groupPropertyKeysKnownAbsent("organization", groupWithNull).build(),
                ),
            )
            assertEquals(0, dispatcher.flagsBodies.size)
        }
    }

    @Test
    fun `more than 1000 keys returns the unavailable result without evaluating or sending a request`() {
        withPostHog(
            createLocalEvaluationResponseFrom(personFlag("profile-incomplete", "is_not_set")),
            createMultipleFlagsResponse("profile-incomplete" to true),
        ) { postHog, dispatcher ->
            val tooMany = List(1001) { "plan" }
            val snapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(tooMany).build(),
                )
            assertTrue(snapshot.keys.isEmpty())

            val groupSnapshot =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().groupPropertyKeysKnownAbsent("organization", List(1001) { "tier" }).build(),
                )
            assertTrue(groupSnapshot.keys.isEmpty())

            val flagOptions =
                PostHogFeatureFlagOptions.builder()
                    .defaultValue("fallback")
                    .personPropertyKeysKnownAbsent(tooMany)
                    .build()
            assertEquals("fallback", postHog.getFeatureFlag("user-1", "profile-incomplete", flagOptions))
            assertEquals("fallback", postHog.getFeatureFlagPayload("user-1", "profile-incomplete", flagOptions))
            assertFalse(postHog.isFeatureEnabled("user-1", "profile-incomplete", flagOptions))
            assertNull(
                postHog.getFeatureFlagResult(
                    "user-1",
                    "profile-incomplete",
                    PostHogFeatureFlagResultOptions.builder().personPropertyKeysKnownAbsent(tooMany).build(),
                ),
            )
            assertEquals(0, dispatcher.flagsBodies.size)

            val atLimit =
                postHog.evaluateFlags(
                    "user-1",
                    PostHogEvaluateFlagsOptions.builder().personPropertyKeysKnownAbsent(List(1000) { "plan" }).build(),
                )
            assertTrue(atLimit.isEnabled("profile-incomplete"))
        }
    }
}
