---
"posthog-android": minor
---

Compose Android activity and dialog layers in screenshot-mode session replay, preserving dialog window opacity and pairing complete scenes with current activity metadata. Activity captures honor the configured screenshot pixel format; dialogs use RGB565 only when configured and cropped to proven-opaque bounds with full window opacity, otherwise preserving transparency with ARGB8888. Crop compatible Material dialog captures and reuse capture bitmap capacity across windows. On Android 8.0–8.1, replace dialogs containing masked content, or whose masks cannot be verified, with an opaque mask covering the whole dialog.
