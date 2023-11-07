package com.posthog.android.replay.internal

import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

internal object PostHogEmptyCallback : Window.Callback {
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return false
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean = false

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = false

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean = false

    override fun onCreatePanelView(featureId: Int): View? = null

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = false

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = false

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = false

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = false

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) {
    }

    override fun onContentChanged() {
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
    }

    override fun onAttachedToWindow() {
    }

    override fun onDetachedFromWindow() {
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
    }

    override fun onSearchRequested(): Boolean = false

    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = false

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? = null

    override fun onWindowStartingActionMode(
        callback: ActionMode.Callback?,
        type: Int,
    ): ActionMode? = null

    override fun onActionModeStarted(mode: ActionMode?) {
    }

    override fun onActionModeFinished(mode: ActionMode?) {
    }
}
