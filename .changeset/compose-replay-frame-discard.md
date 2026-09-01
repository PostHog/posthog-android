---
"posthog-android": patch
---

Fix: session replay no longer discards every screenshot on Jetpack Compose apps. A Compose window recomposes on almost every frame, and the default redraw guard can never classify a Compose redraw as animation-only, so it dropped each frame and the recording showed a blank gray screen (and, with no first snapshot, "this recording can't be played"). Compose-rooted windows now use the mask-alignment verification path, which keeps frames through pixel-only redraws. Session replay also logs a warning after several screenshots are discarded in a row, so a blank recording is no longer silent.
