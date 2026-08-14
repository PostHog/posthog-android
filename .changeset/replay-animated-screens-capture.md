---
"posthog-android": patch
---

Add opt-in mask-alignment verification for session replay screenshots. Set `sessionReplayConfig.enableScreenshotMaskAlignmentVerification` to `true` to capture continuously animated screens that the default redraw guard may discard, including screens with indeterminate spinners, animated GIFs, Lottie, Material progress indicators, and Compose infinite animations.

- Draw-dirty tracking is scoped per window, so a redraw in one window does not affect another window's capture.
- When enabled, mask rects are sampled before and after the pixel copy. A redrawn frame is kept only when the rects remain identical, no layout pass ran, and both walks completed with trustworthy geometry.
- Mask verification fails closed when a rendered view cannot be placed, the Compose semantics pass times out, or PixelCopy times out before masks are painted.
