---
'posthog-server': patch
---

Disable the client at setup when `projectToken` is a personal API key (`phx_...`), as for an empty token
