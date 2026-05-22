---
"posthog-android": patch
---

Fix session replay screenshots being dropped on screens with continuous animations (e.g. Lottie). 
Previously, any `onDraw` callback received while PixelCopy was in flight caused the screenshot to be discarded. Introduces `isOnlyAnimationRedraw` to distinguish animation-driven redraws from structural layout changes. 
Uses `View.hasTransientState()` on the decor view, which Android propagates up from any animating descendant, as the signal.
