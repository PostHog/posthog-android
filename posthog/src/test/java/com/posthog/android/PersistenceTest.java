package com.posthog.android;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Before;
import org.junit.Test;

public class PersistenceTest {
    Persistence persistence;

    @Before
    public void setUp() {persistence = new Persistence();}

    @Test
    public void revenue() {
        persistence.putValue("revenue", 3.14);
        assertThat(persistence.get("revenue")).isEqualTo(3.14);
    }
}
