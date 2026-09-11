---
"posthog-android": patch
---

Fix: session replay now tells you why a Jetpack Compose recording is blank. Wireframe capture, which is the default (`sessionReplayConfig.screenshot = false`), only walks classic Android View types, so a Compose window produces an almost empty wireframe tree that plays back as a gray screen. The SDK now logs one warning when it finds a Compose root while wireframe capture is on, and the warning names `sessionReplayConfig.screenshot = true` as the fix. The KDoc on `screenshot`, `maskAllTextInputs` and `maskAllImages` also states this.
