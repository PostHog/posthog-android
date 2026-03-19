# Autoresearch Ideas

## Remaining ideas (diminishing returns expected at 80µs floor)

- **Lazy RRStyle creation in toWireframe**: Only allocate RRStyle when ≥1 property is non-null. Saves ~500+ object allocations per frame in production. Not benchmarkable without Robolectric.
- **Avoid mutableListOf for maskableWidgets in screenshot path**: Pre-size or use reusable list. Minor.
- **String interning for type/inputType fields**: Tiny impact on comparisons.

## Exhausted / not viable
- ~~Parallel walk with callbacks~~ — lambda overhead 2x worse
- ~~IntObjectMap~~ — parallel walk bypasses HashMap
- ~~Iterative stack-based parallelWalk~~ — Pair allocation negates benefit
- ~~HashMap capacity tuning~~ — parallel walk bypasses it
- ~~Reusable HashMap~~ — clear() costly
- ~~Early rejection in properties~~ — rarely triggers (90% match)
- ~~Content hash on RRWireframe~~ — adds overhead for matching nodes
- ~~DiffAccumulator~~ — no improvement over direct lists
- ~~mask() with CharArray.fill~~ — no primary impact
