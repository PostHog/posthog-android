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

### Wins (kept)
1. **Single-pass diffing** (265→153µs, -42%): Replaced 6+ collection operations (associateBy×2, map×2, HashSet×2, filter×2, intersect) with single-pass HashMap approach + `wireframePropertiesEqual` to avoid `copy()` allocations.
2. **Manual hex toRGBColor** (color: 317→18µs, -94%): Replaced `String.format("#%06X", ...)` with manual CharArray hex conversion. 17x faster.
3. **Eliminate seenOldIds HashSet** (153→131µs, -14%): Use `oldMap.remove()` instead of a separate HashSet to track matched items. Remaining entries = removed.
4. **Recursive flattenInto** (131→119µs, -9%): Replaced stack-based iterative flatten with recursive helper that appends directly to a single result list.
5. **diffTrees combined** (119→99µs, -17%): Combined flatten+diff to traverse trees directly into HashMap, eliminating two 781-element flat list allocations.
6. **Integration wiring**: Updated PostHogReplayIntegration.kt to use RRWireframeDiffer.diffTrees and toRGBColor.
7. **Property comparison ordering**: Primitives first, `style` reference equality before deep equals.

### Dead ends (discarded)
- **Pre-count nodes for HashMap sizing**: Extra traversal costs more than resize savings (+17%).
- **HashMap initial capacity 1024**: Over-allocation hurts (+10%).
- **HashMap initial capacity 256/512**: No measurable improvement.
- **Reusable HashMap (clear between frames)**: `map.clear()` on 781 entries is costly (+9%).
- **mask() with CharArray.fill**: Primary unchanged, only mask secondary improved.

### Total improvement: 265µs → ~100µs (62% faster)
