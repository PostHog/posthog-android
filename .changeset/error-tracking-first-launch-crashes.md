---
"posthog-android": patch
---

Error tracking autocapture now installs the uncaught-exception handler on the very first app launch, before the remote config (`/flags`) response arrives, so an uncaught exception in that startup window is no longer missed. Local `errorTrackingConfig.autoCapture` stays the primary gate; remote config acts only as a kill-switch, uninstalling the handler if the resolved config reports `autocaptureExceptions: false`. Ports the iOS fix (#731 / #551).
