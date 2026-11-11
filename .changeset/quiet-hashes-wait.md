---
"posthog": patch
---

Store the person-properties dedup hash only after the `$set` or `$identify` event is queued, and only when the event as sent still processes the person. An update that `beforeSend` dropped or made personless, or one made while opted out, is no longer skipped when it is repeated.
