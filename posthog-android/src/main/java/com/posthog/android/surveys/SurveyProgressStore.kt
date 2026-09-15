package com.posthog.android.surveys

import com.posthog.PostHogConfig
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogSerializer
import com.posthog.surveys.PostHogSurveyResponse
import com.posthog.surveys.Survey
import java.io.StringReader
import java.util.UUID

internal data class SurveyProgress(
    val submissionId: String,
    val questionOrder: List<String>,
    val version: Int = 1,
    val questionIndex: Int = 0,
    val responses: Map<String, StoredSurveyResponse> = emptyMap(),
    val questionText: Map<Int, String> = emptyMap(),
    val language: String? = null,
)

internal data class StoredSurveyResponse(
    val kind: String,
    val text: String? = null,
    val rating: Int? = null,
    val choices: List<String>? = null,
    val clicked: Boolean = false,
) {
    fun toResponse(): PostHogSurveyResponse? =
        when (kind) {
            "text" -> PostHogSurveyResponse.Text(text)
            "rating" -> PostHogSurveyResponse.Rating(rating)
            "single" -> PostHogSurveyResponse.SingleChoice(text)
            "multiple" -> PostHogSurveyResponse.MultipleChoice(choices)
            "link" -> PostHogSurveyResponse.Link(clicked)
            else -> null
        }

    companion object {
        fun from(response: PostHogSurveyResponse): StoredSurveyResponse =
            when (response) {
                is PostHogSurveyResponse.Text -> StoredSurveyResponse("text", text = response.text)
                is PostHogSurveyResponse.Rating -> StoredSurveyResponse("rating", rating = response.rating)
                is PostHogSurveyResponse.SingleChoice -> StoredSurveyResponse("single", text = response.selectedChoice)
                is PostHogSurveyResponse.MultipleChoice -> StoredSurveyResponse("multiple", choices = response.selectedChoices)
                is PostHogSurveyResponse.Link -> StoredSurveyResponse("link", clicked = response.clicked)
            }
    }
}

internal class SurveyProgressStore(private val config: PostHogConfig) {
    private val serializer = PostHogSerializer(config)
    private val lock = config.surveysConfig

    private fun key(survey: Survey): String = "${survey.id}/${survey.currentIteration ?: 0}"

    fun getOrCreate(survey: Survey): SurveyProgress = load(survey) ?: SurveyProgress(UUID.randomUUID().toString(), questionOrder(survey))

    fun questionOrder(survey: Survey): List<String> = survey.questions.map { "${it.type}:${it.id.orEmpty()}" }

    private fun records(): MutableMap<String, Any> {
        val stored = config.cachePreferences?.getValue(PostHogPreferences.SURVEY_PROGRESS) as? Map<*, *>
        return stored?.entries?.mapNotNull { (key, value) ->
            if (key is String && value != null) key to value else null
        }?.toMap()?.toMutableMap() ?: mutableMapOf()
    }

    fun load(survey: Survey): SurveyProgress? =
        synchronized(lock) {
            val generation = lock.resetGeneration
            val json = records()[key(survey)] as? String ?: return@synchronized null
            if (generation != lock.resetGeneration) return@synchronized null
            try {
                val progress = serializer.deserialize<SurveyProgress>(StringReader(json))
                if (progress.version == 1 && progress.submissionId.isNotEmpty() &&
                    progress.questionIndex in survey.questions.indices &&
                    progress.questionOrder == questionOrder(survey) &&
                    progress.responses.values.all { it.toResponse() != null } &&
                    progress.questionText.keys.all { it in survey.questions.indices }
                ) {
                    return@synchronized progress.takeIf { generation == lock.resetGeneration }
                }
            } catch (_: Exception) {
                config.logger.log("Discarding invalid saved survey progress")
            }
            if (generation == lock.resetGeneration) remove(survey)
            null
        }

    fun save(
        survey: Survey,
        progress: SurveyProgress,
    ) = synchronized(lock) {
        val generation = lock.resetGeneration
        if (config.cachePreferences?.isAvailable() == false) return@synchronized
        val records = records()
        records[key(survey)] = serializer.serializeObject(progress) ?: return@synchronized
        writeRecords(records, generation)
        Unit
    }

    fun reconcile(surveys: List<Survey>) =
        synchronized(lock) {
            val generation = lock.resetGeneration
            if (config.cachePreferences?.isAvailable() == false) return@synchronized
            val keys = surveys.filter { it.startDate != null && it.endDate == null }.map(::key).toSet()
            writeRecords(records().filterKeys { it in keys }, generation)
            Unit
        }

    fun remove(survey: Survey) =
        synchronized(lock) {
            val generation = lock.resetGeneration
            val records = records()
            records.remove(key(survey))
            writeRecords(records, generation)
            Unit
        }

    private fun writeRecords(
        records: Map<String, Any>,
        generation: Long,
    ) {
        if (generation == lock.resetGeneration) {
            config.cachePreferences?.setValue(PostHogPreferences.SURVEY_PROGRESS, records)
        }
    }
}
