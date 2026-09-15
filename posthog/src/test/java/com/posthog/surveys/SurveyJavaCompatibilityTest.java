package com.posthog.surveys;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SurveyJavaCompatibilityTest {
    @Test
    public void legacyConstructorAndCopyRemainAvailableToJava() {
        Survey survey = new Survey(
            "survey", "Survey", SurveyType.POPOVER, Collections.emptyList(),
            null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        Survey copy = survey.copy(
            "survey", "Renamed", SurveyType.POPOVER, Collections.emptyList(),
            null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertEquals("survey", survey.getId());
        assertEquals("Renamed", copy.getName());
    }
}
