---
"posthog-android": patch
---

Fix session replay screenshots being discarded for as long as any continuously-animating content is on screen (indeterminate spinners such as `ProgressDialog`, animated GIFs, Lottie, Material progress indicators, Compose infinite animations), which left whole loading screens missing from replays.

- Draw-dirty tracking is now scoped per window, so an animating dialog (e.g. a loader) no longer discards captures of the static activity behind it.
- Instead of discarding any frame whose window redrew during capture unless an animation-type heuristic matched (`hasTransientState`, surface/texture views), the recorder now proves mask alignment directly: mask rects are sampled before and after the pixel copy, and the frame is kept when they are identical, no layout pass ran, and the walks saw nothing untrustworthy. Pixel-only animation redraws pass this check regardless of which library drives them; structural changes still discard the frame.
- Fail-closed hardening: a mask walk is poisoned (frame discarded) when it meets a rendered view whose geometry is momentarily unknowable (legacy view animation, transient state) or when the Compose semantics pass times out, and a timed-out PixelCopy no longer ships the bitmap before masks are painted.
