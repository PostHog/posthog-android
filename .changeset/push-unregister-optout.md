---
"posthog-android": patch
---

Fix: opting out no longer strands an in-flight push unregister. The unregister `DELETE` is data removal, so it now completes even after `setOptOut(true)` instead of leaving the server-side subscription active for the whole opted-out period (#675).
