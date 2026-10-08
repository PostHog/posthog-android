---
"posthog-android": minor
---

Compose Android activity and dialog layers in screenshot-mode session replay, preserving dialog window opacity and pairing complete scenes with current activity metadata. Activity captures honor the configured screenshot pixel format; dialogs preserve transparency with ARGB8888. Reuse capture bitmap capacity across windows. Cache successful window images independently so dialog updates and capture failures do not repeat unchanged Activity readbacks. On Android 8.0–8.1, replace dialogs containing masked content, or whose masks cannot be verified, with an opaque mask covering the whole dialog.
