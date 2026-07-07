---
"posthog-android": patch
---

fix: stop session replay from dropping screenshots while an animated loader (indeterminate ProgressBar / ProgressDialog spinner) is on screen. The draw-dirty guard now recognises a spinning loader as an animation-only redraw and keeps capturing it, while still discarding genuinely structural changes to keep masks aligned.
