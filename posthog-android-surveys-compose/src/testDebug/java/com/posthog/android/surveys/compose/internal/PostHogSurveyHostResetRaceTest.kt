package com.posthog.android.surveys.compose.internal

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.android.surveys.compose.PostHogSurveysComposeDelegate
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.surveys.PostHogDisplayOpenQuestion
import com.posthog.surveys.PostHogDisplaySurvey
import com.posthog.surveys.PostHogDisplaySurveyTextContentType
import com.posthog.surveys.PostHogSurveyPresentation
import com.posthog.surveys.PostHogSurveyPresentationSession
import com.posthog.surveys.PostHogSurveysConfig
import com.posthog.surveys.PostHogSurveysResetAwareDelegate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class PostHogSurveyHostResetRaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun `reset racing a new owner bind cannot lose invalidation`() {
        val delegate = PostHogSurveysComposeDelegate(ApplicationProvider.getApplicationContext<Application>())
        val notification = CountDownLatch(1)
        val config =
            PostHogConfig("bind-reset", "http://127.0.0.1:1").apply {
                cachePreferences = PostHogMemoryPreferences()
                preloadFeatureFlags = false
                remoteConfig = false
                surveysConfig.surveysDelegate =
                    object : PostHogSurveysResetAwareDelegate by delegate {
                        override fun onSurveyReset(
                            resetGeneration: Long,
                            config: PostHogSurveysConfig,
                        ) {
                            delegate.onSurveyReset(resetGeneration, config)
                            notification.countDown()
                        }
                    }
            }
        val sdk = PostHog.with(config)
        val owner = PostHogSurveyPresentationSession(config.surveysConfig)
        val host = PostHogSurveysComposeDelegate::class.java.getDeclaredField("host").apply { isAccessible = true }.get(delegate)
        val gate = checkNotNull(PostHogSurveyHost::class.java.getDeclaredField("resetLock").apply { isAccessible = true }.get(host))
        val binder = Thread { delegate.bindSurveySession(owner) }
        val resetter = Thread { sdk.reset() }
        val oldGeneration = config.surveysConfig.resetGeneration
        try {
            compose.activityRule.scenario.recreate()
            synchronized(gate) {
                binder.start()
                awaitBlocked(binder)
                resetter.start()
                // The original race delivers reset before bind installs the new owner. With
                // atomic binding reset waits for that installation, then invalidates it.
                notification.await(2, TimeUnit.SECONDS)
            }
            binder.join(2000)
            resetter.join(2000)
            assertFalse(binder.isAlive)
            assertFalse(resetter.isAlive)
            val survey =
                PostHogDisplaySurvey(
                    "old",
                    "Old",
                    listOf(
                        PostHogDisplayOpenQuestion(
                            "q",
                            "Previous user question",
                            null,
                            PostHogDisplaySurveyTextContentType.TEXT,
                            false,
                            "Send",
                        ),
                    ),
                )
            compose.runOnIdle {
                delegate.renderSurvey(
                    PostHogSurveyPresentation(survey, oldGeneration, owner),
                    {},
                    { _, _, _ -> null },
                    {},
                )
            }
            compose.onNodeWithText("Previous user question").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { delegate.cleanupSurveys() }
            sdk.close()
        }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertTrue(thread.state == Thread.State.BLOCKED, "Binder must be waiting on the held host gate")
    }
}
