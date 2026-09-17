# Android element interaction capture

Enable before SDK setup (off by default):

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    captureElementInteractions = true
    // Independent of replay; replay can remain disabled.
    sessionReplay = false
}
PostHogAndroid.setup(application, config)
```

A single-pointer tap on an enabled, clickable Android View or Compose semantics `OnClick`
target produces `$autocapture`. Scrolls, drags beyond touch slop, long presses, cancelled
sequences and multi-touch do not. Input is observed, never consumed or redispatched. This
captures the tap, not a guarantee that the app's click handler succeeded. Target properties
are collected before the handler can change or navigate away from the screen.

Existing `optOut`, `beforeSend`, `close`, `$session_id` and `$screen_name` behavior applies.
The first enabled SDK instance owns the interaction integration. Closing a secondary instance
does not remove another instance's observers. There is no new start/stop API; configure at setup.

## Privacy and subtree exclusions

Only element types/roles, resource entry IDs or Compose test tags, sibling positions and
window-local touch coordinates in dp are sent. No text, content descriptions, input values,
arbitrary View tags, URLs or object hashes are serialized. In particular, v1 does **not** send `$el_text`. Use stable,
non-sensitive test tags: tags are identifiers, not a place for user data.

Exclude a View and all its descendants using the SDK's **qualified resource ID**:

```kotlin
container.setTag(com.posthog.android.R.id.posthog_autocapture_ignore, true)
// Restore subsequent interactions:
container.setTag(com.posthog.android.R.id.posthog_autocapture_ignore, false)
```

For Compose:

```kotlin
import com.posthog.android.PostHogAutocaptureModifier.postHogAutocaptureIgnore

Column(Modifier.postHogAutocaptureIgnore(isEnabled = true)) {
    Button(onClick = { /* app action */ }) { Text("Continue") }
}
```

Exclusions are re-evaluated on subsequent taps, including changes during a gesture. Compose
exclusions also apply to embedded `AndroidView` targets. The modifier does not change replay
masking. Existing explicit replay masking (`ph-no-capture` View tag/content-description marker,
`Modifier.postHogMask()`) excludes the interaction subtree as well; replay unmasking does not
override an interaction exclusion. Compose password semantics are excluded. Global replay
text/image masking does not disable all interaction capture, because no text/images are sent.

## Event mapping

`$event_type = "touch"` and `$touch_x` / `$touch_y` follow the mobile event convention.
Coordinates describe the completed tap in window-local density-independent units (Android dp,
matching iOS points), not screen pixels. They are snapshotted before the app handles the tap.
This follows posthog-ios commit `143d8337e9d39156f00fb5bde6c191a57febef1e`
(`PostHogRageClickIntegration.swift` / `PostHogSDK.swift`).

`$ce_version = 1`, `$elements` and `$elements_chain` preserve the browser structural schema.
Both element representations are sent for compatibility with downstream consumers.
Elements are target-first, then ancestors:

- `tag_name`: lowercase native View simple class name; Compose role (`button`, `checkbox`,
  `switch`, `radio`, `tab`, `image`), or `compose` when no supported role is supplied.
- `attr__id`: native resource **entry** name (not numeric/generated View IDs), or Compose test tag.
- `nth_child`, `nth_of_type`: one-based positions among native/semantic siblings.

The chain follows posthog-js commit `e96852dbe48690a18e40afe4bb423afb260a28d4`
(`autocapture-utils.ts`, `extractElements` / `elementsToString`): lexical attribute ordering,
quote escaping, target-first semicolon-separated elements. `attr__id` is also represented as
`attr_id` in the chain, matching that implementation. No fake DOM/page context is added.

Identifiers/types are limited to 128 UTF-16 code units, output to 20 elements, and hit traversal
to 512 nodes / 32 levels per native or Compose tree. Incomplete/unsupported traversal skips
the interaction rather than emitting a potentially wrong or excluded target. Native interop
uses its native ancestor chain; Compose-only targets include semantic and host View ancestors.

## Coverage and limitations

- Works without replay and without the optional Compose runtime. Uses the existing Curtains
  dependency to observe current and future Activity/Dialog windows, including deferred setup.
- Resolves nearest actionable ancestors, ordinary sibling/elevation overlap, native scroll and
  View matrices, rectangular clipping, and Compose's clipped semantic bounds.
- Only main-thread, touch-driven interactions with recognizable actionable targets are supported.
  Accessibility/keyboard/programmatic clicks, raw Canvas/custom gesture targets without click
  semantics, WebView DOM elements, and roots without a Curtains `phoneWindow` (`PopupWindow`,
  Compose `PopupLayout`, raw `WindowManager` overlays) are not captured. Activity and Dialog
  windows are supported; this is not all-window coverage. Unsupported roots never fall back to
  a target in an underlying window. Custom native child drawing order, nonrectangular clipping,
  and app-specific input interception can differ from geometric hit testing. Compose's expanded
  minimum touch-target regions outside semantic bounds are not inferred.
- Hierarchies beyond the safety bounds and incompatible Compose implementations are skipped.
  Class names can change with app obfuscation; resource IDs/test tags are preferable identifiers.
- Rage/dead-click detection is not part of this option.

## Sample and checks

The Android sample's **Element interactions** screen contains responsive, no-op and ignored
View/Compose buttons with static identifiers. The sample opts in through `MyApp`; to verify
independence, set `sessionReplay = false`. Supply your own local SDK credentials using the
sample's existing setup; no new credential is needed in this screen.

Focused checks:

```shell
./gradlew :posthog-android:testDebugUnitTest --tests '*Interaction*Test'
./gradlew :posthog-samples:posthog-android-sample:testDebugUnitTest --tests '*InteractionActivityTest'
make test
make checkFormat
make api
```

The sample test uses real Compose and embedded Views, dispatches through the Window, and
intercepts events with `beforeSend` rather than sending them to a service.
