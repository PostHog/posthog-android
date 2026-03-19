# Autoresearch Ideas

## Deferred Optimizations

- **Reuse IntArray(2) for coordinates in toWireframe**: Currently allocates per view. Could use a field-level reusable array. (Android-only, not in current benchmark)
- **Avoid `mutableListOf<RRMutatedNode>()` in generateSnapshot**: Pre-size based on diff result counts. (Android-only)
- **Lazy RRStyle creation**: Only create style when at least one property is non-null. (Android-only)
- **Parallel tree walk for diffTrees**: When trees have identical structure (common case), compare nodes at same positions without HashMap. Could bypass HashMap entirely for ~90% of nodes.
- **Open-addressing IntObjectMap for diffing**: Replace HashMap<Int, RRWireframe> with a primitive int-keyed map to avoid boxing. Custom implementation needed (no deps).
- **Structural hash on RRWireframe**: Pre-compute a hash of all non-child fields during construction. Compare hash first, only do field-by-field on hash match. Trades memory for CPU.
- **Avoid Triple allocation in diffTrees**: Return results via callback or mutable holder to avoid Triple + 3 ArrayList allocations per call.

## Pruned (already tried or not viable)
- ~~Pool/reuse RRStyle objects~~ — toWireframe is Android-only, not benchmarkable currently
- ~~Cache mask strings by length~~ — mask is already fast (26-48µs for 500 strings)
- ~~Use StringBuilder instead of string templates~~ — no string templates in hot path
- ~~@JvmField on RRWireframe~~ — it's a data class, properties are already fields
- ~~Reuse coordinates IntArray in toScreenshotWireframe~~ — Android-only
- ~~Move away from PixelCopy thread~~ — architectural, not algorithmic
- ~~Reusable HashMap~~ — tried, clear() is costly
- ~~HashMap capacity tuning~~ — tried 128/256/512/1024, 128 is optimal
