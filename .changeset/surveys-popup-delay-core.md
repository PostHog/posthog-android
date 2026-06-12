---
"posthog": minor
---

Surveys: expose `surveyPopupDelaySeconds` on the public `PostHogDisplaySurveyAppearance` and map it
from the internal `SurveyAppearance`, so survey UIs can honor the configured popup delay before
presenting a survey.
