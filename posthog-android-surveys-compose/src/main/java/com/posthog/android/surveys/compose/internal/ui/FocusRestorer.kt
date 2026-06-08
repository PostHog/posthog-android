package com.posthog.android.surveys.compose.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Restores a field's focus across a configuration change.
 *
 * `rememberSaveable` brings a field's value back after a rotation / dark-mode /
 * font-size change, but Compose does not restore focus. This modifier persists
 * whether the field was focused and re-requests focus on re-entry; a field that
 * wasn't focused stays unfocused.
 *
 * The restored flag is captured once (via [remember]) before the field's first
 * `onFocusChanged` reports `Inactive` and would otherwise reset it, so the
 * re-request still runs. Apply to any focusable field whose focus should survive
 * a configuration change:
 *
 * ```
 * BasicTextField(modifier = Modifier.restoreFocusOnReentry(), ...)
 * ```
 */
@Composable
internal fun Modifier.restoreFocusOnReentry(): Modifier {
    val focusRequester = remember { FocusRequester() }
    var wasFocused by rememberSaveable { mutableStateOf(false) }
    val restoreFocus = remember { wasFocused }
    LaunchedEffect(Unit) {
        if (restoreFocus) {
            focusRequester.requestFocus()
        }
    }
    return this
        .focusRequester(focusRequester)
        .onFocusChanged { wasFocused = it.isFocused }
}
