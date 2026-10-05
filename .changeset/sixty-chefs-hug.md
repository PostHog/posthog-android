---
"posthog-android": minor
---

Compose Android activity and dialog layers in screenshot-mode session replay, preserving the configured screenshot pixel format and pairing complete scenes with current activity metadata. Crop compatible Material dialog captures and reuse capture bitmap capacity across windows. On Android 8.0–8.1, replace dialogs containing masked content, or whose masks cannot be verified, with an opaque mask covering the whole dialog.
