---
"posthog-android": minor
---

Capture Jetpack Compose content in the default wireframe session replay mode. Compose draws its whole UI into a single `AndroidComposeView` with no child Views, so the wireframe walk previously bottomed out at the decor view and Compose screens recorded as a blank screen, with nothing in the logs to explain it. Compose content is now read from the semantics tree and emitted as text, input, and image wireframes, honouring `maskAllTextInputs`, passwords, and the `postHogMask`/`postHogUnmask` modifiers. Screenshot mode (`sessionReplayConfig.screenshot = true`) still gives the highest fidelity, and the SDK now logs that recommendation once when it detects Compose in wireframe mode.
