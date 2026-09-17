# Android element interaction capture

Enable before SDK setup (off by default):

```kotlin
val config = PostHogAndroidConfig(apiKey).apply {
    captureElementInteractions = true
    captureRageClicks = true
    captureDeadClicks = true
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
- `captureElementInteractions` does not enable the separate rage/dead options.

## Rage and dead taps

`captureRageClicks` and `captureDeadClicks` are independent, default-false siblings. Detector-only
configuration does **not** emit `$autocapture` and works with replay disabled. They share tap
recognition, target extraction, coordinates, exclusions and all common event properties above.
Recognized editable, slider and scrolling controls are excluded from detectors because repetition
is normal there (buttons inside scrolling containers remain eligible).

- **`$rageclick`**: four taps on the same target/window within one second, with all tap locations
  within 50dp of one another. Uses monotonic time, at most four history entries, and emits once
  per continuous burst. A quiet second or target/window change starts a new burst. No extra
  count/duration properties are sent.
- **`$dead_click`**: the latest eligible tap has no observed meaningful response for three seconds.
  The event retains the original tap timestamp and properties. Diagnostics are
  `$dead_click_event_timestamp` (epoch ms), `$dead_click_absolute_delay_ms` (monotonic elapsed ms),
  and `$dead_click_absolute_timeout = true`. Unsupported browser/DOM diagnostics are omitted.

Dead detection observes supported native View and Compose semantic content/state, layout, scroll,
focus and window changes. The baseline is taken **before** the app handler, then checked immediately
after dispatch and at most ten times per second during observation. Pressed/ripple-only feedback
is not a meaningful response. Only one pending candidate/timer is retained; later input replaces
or cancels it. Snapshots share a total 512-node/32-level bound and a 16,384 UTF-16 code-unit content
budget; incomplete walks, unknown native/custom View classes, SurfaceView, TextureView, WebView,
detachment or observation gaps over 500ms skip detection. Changes anywhere in the observed window
can conservatively suppress a dead event, even if unrelated to the tap.

**Local content processing differs from event-property collection:** dead detection transiently
reads unmasked, noneditable UI text/content/state solely to compare ephemeral, per-observation
salted digests. No raw text is retained across walks. Neither text nor digests are serialized,
logged or sent; digests are discarded when the bounded observation ends. Excluded, replay-masked,
password and editable subtrees are observed structurally only; their content is not read. Global
replay text masking is not an interaction-content-processing setting: if local processing is not
acceptable, leave dead detection disabled or explicitly exclude the relevant subtrees.

Dead taps are a **heuristic for absence of an observable response**, not proof that a handler failed.
Semantics-backed Compose is supported, but Canvas/drawBehind or other drawing-only responses with
unchanged semantics are unsupported and can produce false positives. Explicitly exclude those
controls/subtrees with `postHogAutocaptureIgnore`. Arbitrary drawable internals and off-screen/network
responses are not inferred. Known unsupported native trees and incomplete observations fail closed.

Pending detections and rage history are invalidated on navigation (`screen`), backgrounding, root
additions/removals (including unsupported popups), identity/reset, consent changes, sessions and close.
Delayed emission rechecks targets/exclusions and uses a guarded enqueue so a reentrant `beforeSend`
identity or consent change cannot send an old-user candidate. Hooks may drop detector events normally;
rewriting a detector event into an exception/replay record is unsupported and drops it. Session checks also preserve the original non-null tap session and timestamp, and reject hook
mutations of those values. In the inherited global-session architecture, session rotation is not
atomically serialized with enqueue: a final-check/enqueue race can emit a late event attributed to
its **original tap session**, never silently moved to the new session. This limitation is separate
from the guarded identity/consent guarantees. Custom
`PostHogInterface` implementations without the concrete SDK's internal guard skip detectors.

## Sample and checks

The Android sample's **Element interactions** screen contains responsive, no-op and ignored
View/Compose buttons with static identifiers, including an explicitly ignored drawing-only control. The sample opts in through `MyApp`; to verify
independence, set `sessionReplay = false`. Supply your own local SDK credentials using the
sample's existing setup; no new credential is needed in this screen.

Focused checks:

```shell
./gradlew :posthog-android:testDebugUnitTest --tests '*Interaction*Test'
./gradlew :posthog-samples:posthog-android-sample:testDebugUnitTest --tests '*Interaction*Test'
make test
make checkFormat
make api
```

The sample test uses real Compose and embedded Views, dispatches through the Window, and
intercepts events with `beforeSend` rather than sending them to a service.
