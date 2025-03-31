import com.posthog.surveys.SurveyAppearance
import com.posthog.surveys.SurveyConditions
import com.posthog.surveys.SurveyFeatureFlagKeyValue
import com.posthog.surveys.SurveyQuestion
import com.posthog.surveys.SurveyType
import java.util.Date

public data class Survey(
    val id: String,
    val name: String,
    val type: SurveyType,
    val questions: List<SurveyQuestion>,
    val description: String?,
    val featureFlagKeys: List<SurveyFeatureFlagKeyValue>?,
    val linkedFlagKey: String?,
    val targetingFlagKey: String?,
    val internalTargetingFlagKey: String?,
    val conditions: SurveyConditions?,
    val appearance: SurveyAppearance?,
    val currentIteration: Int?,
    val currentIterationStartDate: Date?,
    val startDate: Date?,
    val endDate: Date?,
)
