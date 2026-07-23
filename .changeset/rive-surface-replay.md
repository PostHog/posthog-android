---
"posthog-android": patch
---

Fix session replay capturing nothing on screens with a continuously-rendering surface/texture-backed view (e.g. Rive).
Such libraries render on their own worker thread and never set `View.hasTransientState()`, so every frame was discarded as a "screen change". The animation-only redraw heuristic now also detects an actively-rendering `SurfaceView`/`TextureView` in the tree, whose geometry stays stable so masks remain aligned, and keeps the frame instead of dropping it.
