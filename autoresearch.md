# Autoresearch: PostHogReplayIntegration Runtime Performance

## Objective
Optimize the runtime performance and memory footprint of `PostHogReplayIntegration.kt` — the core session replay engine for the PostHog Android SDK. This class runs on every frame draw, traversing the view tree, generating wireframes, and diffing snapshots. The hot path executes hundreds of times per second on user devices, so every allocation and CPU cycle matters.

## Key Hot Paths (ranked by impact)
1. **Snapshot diffing** (`flattenChildren` + `findAddedAndRemovedItems`) — runs every frame after the first. Creates ~10 intermediate collections per call for a typical 200-node view tree.
2. **`toWireframe`** — recursive view tree traversal creating `RRWireframe` + `RRStyle` + child lists per node.
3. **`Int.toRGBColor()`** — uses `String.format("#%06X", ...)` on every colored view. `String.format` is notoriously slow on Android.
4. **`String.mask()`** — `"*".repeat(length)` per masked text view.
5. **`findMaskableWidgets`** — recursive traversal with `Rect` allocations per maskable view.
6. **`Drawable.base64()` / `Bitmap.webpBase64()`** — image encoding (expensive but hard to optimize algorithmically).

## Metrics
- **Primary**: `total_µs` (µs, lower is better) — wall-clock time for `flattenChildren` + `findAddedAndRemovedItems` + `toRGBColor` over a realistic workload.
- **Secondary**: `alloc_count` — number of object allocations (approximated by collection creations in the hot path).

## How to Run
`./autoresearch.sh` — outputs `METRIC name=number` lines.

## Benchmark Strategy
Since `PostHogReplayIntegration` methods are private and depend on Android views, we benchmark at two levels:

1. **Pure JVM benchmark** (primary): Extract the hot-path algorithmic code (`flattenChildren`, `findAddedAndRemovedItems`, `toRGBColor`) into internal utility functions in the `posthog` core module (pure Kotlin/JVM). Benchmark with synthetic RRWireframe trees (200-500 nodes, realistic depth). This runs fast (~1s), is deterministic, and captures the main optimization surface.

2. **Robolectric integration test** (secondary/validation): After optimizing core algorithms, validate that the full `toWireframe` path still works correctly and isn't regressed.

## Files in Scope
- `posthog-android/src/main/java/com/posthog/android/replay/PostHogReplayIntegration.kt` — main integration, view tree traversal, masking, screenshot
- `posthog/src/main/java/com/posthog/internal/replay/RRWireframe.kt` — wireframe data class
- `posthog/src/main/java/com/posthog/internal/replay/RRStyle.kt` — style data class
- `posthog-android/src/main/java/com/posthog/android/internal/PostHogAndroidUtils.kt` — `densityValue`, `webpBase64`
- `posthog-android/src/main/java/com/posthog/android/replay/internal/Throttler.kt` — throttle logic
- `posthog-android/src/main/java/com/posthog/android/replay/internal/ViewTreeSnapshotStatus.kt` — snapshot state

## Off Limits
- Public API signatures (no breaking changes)
- Test files (don't modify existing tests, only add new benchmark tests)
- Build configuration files
- Other modules (`posthog-server`, samples, gradle plugin)

## Constraints
- Existing tests must pass (`make testJava`)
- No new external dependencies
- Code must pass `make checkFormat`
- All optimizations must be safe for concurrent access (the hot path runs on a background executor)
- Preserve correctness of masking/diffing logic exactly

## Optimization Ideas (prioritized)
1. **Eliminate `flattenChildren` intermediate lists**: Use a pre-sized ArrayList or iterate in-place with a stack instead of recursive list concatenation.
2. **Single-pass diffing**: Replace the multi-pass `findAddedAndRemovedItems` (6+ collection operations) with a single pass using a HashMap.
3. **Replace `String.format` in `toRGBColor`**: Use manual hex conversion with a char array — 10-50x faster on Android.
4. **Avoid `data class copy()` in diffing**: Instead of `copy(childWireframes = null)` for comparison, compare fields individually to avoid allocation.
5. **Pre-size collections**: `mutableListOf()` → `ArrayList(estimatedSize)` in hot paths.
6. **Cache `mask()` results**: Common text lengths produce the same mask string.
7. **Reuse wireframe tree structure**: Instead of rebuilding the full tree each frame, update in-place where possible.

## What's Been Tried
_Nothing yet — establishing baseline._
