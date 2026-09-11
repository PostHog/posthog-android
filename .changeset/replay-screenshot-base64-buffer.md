---
'posthog-android': patch
---

Cut the heap a session replay screenshot needs. The Base64 encoder buffer was sized from the bitmap's `allocationByteCount`, so every capture reserved a second full uncompressed frame, about 10 MB on a 1080x2400 screen, for a payload that lands in the tens of kilobytes. The buffer is now sized from a compressed estimate, and the compressed bytes stream straight into the encoder instead of being held in an intermediate array. Devices with a small heap were running out of memory on this allocation at the default 1 second capture cadence.
