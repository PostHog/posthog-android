# Autoresearch Ideas

## Remaining ideas (all at diminishing returns — 78µs floor reached)

- **Lazy RRStyle creation in toWireframe**: Only allocate when ≥1 property is non-null. Saves ~500+ allocations/frame. Android-only, not JVM benchmarkable.
- **HashSet → primitive IntSet for visitedViews in findMaskableWidgets**: Avoids boxing. Android-only.
- **ByteArrayOutputStream pre-sizing in webpBase64**: Current uses `allocationByteCount` (full pixel data) but compressed output is ~10x smaller. Android-only.

## Exhausted (tried and confirmed no improvement)
- Parallel walk with callbacks — lambda overhead 2x worse
- IntObjectMap — parallel walk bypasses HashMap
- Iterative stack parallelWalk — Pair allocation negates benefit
- HashMap capacity tuning — 128 optimal, parallel walk bypasses it
- Reusable HashMap — clear() costly
- Early rejection in properties — rarely triggers
- Content hash on RRWireframe — adds overhead for matching nodes
- DiffAccumulator — no improvement over direct lists
- mask() with CharArray.fill — no primary impact
- Lazy orphan lists via arrayOfNulls — net zero
- Index-based loops — JVM scalarizes iterators
