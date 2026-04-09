# Autoresearch Ideas — FINAL

## Optimization surface exhausted at ~60µs (77% from 265µs baseline)

At 60µs for 781 nodes, we're at ~77ns/node. The inner loop does ~34 field comparisons
per node (17 wireframe + 17 style) at ~2.3ns per comparison — near hardware limits.

## Remaining Android-only ideas (not JVM benchmarkable)
- **Lazy RRStyle creation in toWireframe**: Skip allocation when all fields null. ~500 allocs/frame saved.
- **ByteArrayOutputStream pre-sizing in webpBase64**: Use compressed size estimate instead of full pixel data.
- **String interning for toRGBColor output**: Let `===` succeed in styleEquals for repeated colors.

## Fully exhausted approaches
- Parallel walk variants (callbacks, iterative stack, DiffAccumulator, lazy orphans)
- HashMap alternatives (IntObjectMap, capacity tuning, reusable map)
- Comparison optimizations (content hash, early rejection, field reordering)
- Collection optimizations (all pre-sized, lazy allocation where beneficial)
- Loop optimizations (index-based, while loops)
- mask() alternatives (CharArray.fill, Arrays.fill)
- Code duplication for same-size fast path (JIT handles it)
