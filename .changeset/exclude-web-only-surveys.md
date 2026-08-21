---
"posthog-android": patch
---

Fix surveys scoped to web via a CSS selector or URL display condition leaking onto native Android. Surveys with `conditions.url` and/or `conditions.selector` are now excluded from active matching surveys, since those conditions can only be evaluated in a browser DOM. This mirrors the exclusion already shipped in posthog-ios and posthog-react-native.
