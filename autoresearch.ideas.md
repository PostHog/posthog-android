# Autoresearch Ideas

## Deferred Optimizations

- **Reuse IntArray(2) for coordinates in toWireframe**: Currently allocates per view. Could use a thread-local or field-level reusable array.
- **Pool/reuse RRStyle objects**: toWireframe creates a new RRStyle per view. Could pool and reset.
- **Reuse coordinates IntArray in toScreenshotWireframe**: Same as toWireframe.
- **Avoid `mutableListOf<RRMutatedNode>()` in generateSnapshot**: Pre-size based on diff result counts.
- **Cache mask strings by length**: Common text lengths (e.g., 5, 10, 20 chars) produce the same mask. LRU cache could avoid allocation.
- **Use StringBuilder instead of string templates in toWireframe**: Some string interpolation in "Hello World $myId" patterns.
- **Lazy RRStyle creation**: Only create style when at least one property is non-null.
- **Consider using `@JvmField` on RRWireframe properties**: Avoids getter call overhead.
- **Parallel tree walk for diffTrees**: When trees have identical structure (common case), compare nodes at same positions without HashMap.
- **Move away from PixelCopy thread to coroutine-based approach**: Could reduce thread management overhead.
