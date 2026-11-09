---
'posthog-android': major
---

- **Breaking:** remove wireframe session replay and the `screenshot`, `drawableConverter` and `debouncerDelayMs` options of `PostHogSessionReplayConfig` (use `throttleDelayMs` for the last); replay always records masked screenshots, so apps that relied on the default now upload screenshots and should mask custom-drawn views, maps and video, or turn replay off
- **Breaking:** apply `Modifier.postHogMask()` and `Modifier.postHogUnmask()` only to the element and its children, mask Compose text that draws past its layout box in full, and mask nodes using `Modifier.clearAndSetSemantics` while text or image masking is on
- **Breaking:** send a dialog's dim and a dialog masked whole as screenshot images instead of styled wireframe shapes
- **Breaking:** change `$locale` to the first language in the device settings as a full language tag, such as `zh-Hant-TW`, instead of `Locale.getDefault()`
- **Breaking:** skip deep link query parameters whose name starts with `$` in the `Deep Link Opened` event
- **Breaking:** make SDK-internal helpers in `com.posthog.android.internal` and `com.posthog.android.replay.internal` non-public
- Always set `$sdk_debug_replay_capture_mode` to `screenshot`
