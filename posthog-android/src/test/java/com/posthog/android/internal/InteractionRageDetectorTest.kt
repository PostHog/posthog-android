package com.posthog.android.internal

import android.app.Activity
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class InteractionRageDetectorTest {
    private val root = View(Robolectric.buildActivity(Activity::class.java).get())
    private val target = InteractionTarget(root, elements = listOf(InteractionElement("button")))
    private val sut = InteractionRageDetector()

    @Test
    fun `four taps within one second emit only once per continuous burst`() {
        repeat(3) { assertFalse(sut.tap(root, target, 1, it * 200L, 20f, 20f)) }
        assertTrue(sut.tap(root, target, 1, 600, 20f, 20f))
        repeat(10000) { assertFalse(sut.tap(root, target, 1, 601L + it, 20f, 20f)) }
        repeat(3) { assertFalse(sut.tap(root, target, 1, 12000L + it, 20f, 20f)) }
        assertTrue(sut.tap(root, target, 1, 12003, 20f, 20f))
    }

    @Test
    fun `total duration not merely intertap delay is bounded`() {
        repeat(100) { assertFalse(sut.tap(root, target, 1, it * 400L, 20f, 20f)) }
    }

    @Test
    fun `spatial drift and radius violations do not aggregate`() {
        repeat(10) { assertFalse(sut.tap(root, target, 1, it * 10L, it * 30f, 20f)) }
    }

    @Test
    fun `different targets windows and generations reset history`() {
        repeat(3) { sut.tap(root, target, 1, it.toLong(), 20f, 20f) }
        assertFalse(sut.tap(root, target, 2, 3, 20f, 20f))
        val other = InteractionTarget(root, semanticsId = 2, elements = target.elements)
        repeat(3) { assertFalse(sut.tap(root, other, 2, 4L + it, 20f, 20f)) }
        val window = View(root.context)
        assertFalse(sut.tap(window, other, 2, 7, 20f, 20f))
        sut.reset()
        assertFalse(sut.tap(window, other, 2, 8, 20f, 20f))
    }

    @Test
    fun `editable controls invalid coordinates and backwards clocks reset history`() {
        val editable = InteractionTarget(root, elements = target.elements, repetitive = true)
        repeat(10) { assertFalse(sut.tap(root, editable, 1, it.toLong(), 20f, 20f)) }
        repeat(3) { sut.tap(root, target, 1, it + 100L, 20f, 20f) }
        assertFalse(sut.tap(root, target, 1, 0, 20f, 20f))
        assertFalse(sut.tap(root, target, 1, 1, Float.NaN, 20f))
    }

    @Test
    fun `outlying oldest point does not discard a valid recent spatial window`() {
        assertFalse(sut.tap(root, target, 1, 0, 0f, 0f))
        assertFalse(sut.tap(root, target, 1, 1, 45f, 0f))
        assertFalse(sut.tap(root, target, 1, 2, 55f, 0f))
        assertFalse(sut.tap(root, target, 1, 3, 60f, 0f))
        assertTrue(sut.tap(root, target, 1, 4, 65f, 0f))
    }
}
