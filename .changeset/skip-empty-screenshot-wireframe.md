---
"posthog-android": patch
---

Session Replay (screenshot mode): skip a frame when the screenshot capture is discarded instead of sending an imageless `screenshot` wireframe. An empty wireframe was rendered by the player as a placeholder tile, causing a brief visual flash before the next successful capture.
