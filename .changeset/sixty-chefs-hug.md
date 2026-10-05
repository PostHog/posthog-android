---
"posthog-android": minor
---

Compose Android activity and dialog layers in screenshot-mode session replay. Preserve the configured Activity pixel format while retaining dialog transparency, and reuse capture bitmap capacity across pixel formats. On Android 8.0–8.1, replace dialogs containing masked content, or whose masks cannot be verified, with an opaque mask covering the whole dialog.
