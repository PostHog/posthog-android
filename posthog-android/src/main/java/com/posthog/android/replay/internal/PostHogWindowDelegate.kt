package com.posthog.android.replay.internal

import android.os.Build
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

internal open class PostHogWindowDelegate(val callback: Window.Callback) : Window.Callback {
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return callback.dispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean {
        return callback.dispatchKeyShortcutEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        return callback.dispatchTouchEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean {
        return callback.dispatchTrackballEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        return callback.dispatchGenericMotionEvent(event)
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean {
        return callback.dispatchPopulateAccessibilityEvent(event)
    }

    override fun onCreatePanelView(featureId: Int): View? {
        return callback.onCreatePanelView(featureId)
    }

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
        return callback.onCreatePanelMenu(featureId, menu)
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        return callback.onPreparePanel(featureId, view, menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        return callback.onMenuOpened(featureId, menu)
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        return callback.onMenuItemSelected(featureId, item)
    }

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) {
        return callback.onWindowAttributesChanged(attrs)
    }

    override fun onContentChanged() {
        return callback.onContentChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        return callback.onWindowFocusChanged(hasFocus)
    }

    override fun onAttachedToWindow() {
        return callback.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        return callback.onDetachedFromWindow()
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
        return callback.onPanelClosed(featureId, menu)
    }

    override fun onSearchRequested(): Boolean {
        return callback.onSearchRequested()
    }

    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            callback.onSearchRequested(searchEvent)
        } else {
            return false
        }
    }

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? {
        return this.callback.onWindowStartingActionMode(callback)
    }

    override fun onWindowStartingActionMode(
        callback: ActionMode.Callback?,
        type: Int,
    ): ActionMode? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.callback.onWindowStartingActionMode(callback, type)
        } else {
            return null
        }
    }

    override fun onActionModeStarted(mode: ActionMode?) {
        return callback.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        return callback.onActionModeFinished(mode)
    }
}
