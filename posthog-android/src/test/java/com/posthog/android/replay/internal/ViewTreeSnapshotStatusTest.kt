package com.posthog.android.replay.internal

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.internal.replay.RRWireframe
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
internal class ViewTreeSnapshotStatusTest {
    @Test
    fun `reset clears every mutable field`() {
        val status = ViewTreeSnapshotStatus(mock())
        // Dirty every mutable field. If a new field is added and not cleared in reset(), one of the
        // assertions below (or this setup) must be updated — that is the point of the test.
        status.sentFullSnapshot = true
        status.sentMetaEvent = true
        status.keyboardVisible = true
        status.lastSnapshot = mock<RRWireframe>()
        status.isOnDrawnCalled = true
        status.isOnlyAnimationRedraw = true
        status.lastAnimationProbeMs = 123L
        status.animatingProgressBar = WeakReference(View(ApplicationProvider.getApplicationContext()))

        status.reset()

        assertFalse(status.sentFullSnapshot)
        assertFalse(status.sentMetaEvent)
        assertFalse(status.keyboardVisible)
        assertNull(status.lastSnapshot)
        assertFalse(status.isOnDrawnCalled)
        assertFalse(status.isOnlyAnimationRedraw)
        assertEquals(0L, status.lastAnimationProbeMs)
        assertNull(status.animatingProgressBar)
    }
}
