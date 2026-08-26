package com.posthog.internal.errortracking

import com.posthog.PostHogInternal
import java.util.Collections
import java.util.IdentityHashMap

@PostHogInternal
public class ThrowableCoercer {
    /**
     * Decides whether a frame belongs to the user's application (`in_app`).
     *
     * Excludes win over includes: a class matching any [inAppExcludes] prefix is never in-app.
     * With no [inAppIncludes] every remaining frame is in-app (preserves existing Android behavior
     * where the app package is injected automatically).
     */
    private fun isInApp(
        className: String,
        inAppIncludes: List<String>,
        inAppExcludes: List<String>,
    ): Boolean {
        inAppExcludes.forEach { exclude ->
            if (className.startsWith(exclude)) {
                return false
            }
        }

        // if there's nothing, all frames are considered in app
        if (inAppIncludes.isEmpty()) {
            return true
        }

        inAppIncludes.forEach { include ->
            if (className.startsWith(include)) {
                return true
            }
        }

        return false
    }

    /**
     * Marks JVM-synthesized "noise" frames (lambdas, including Kotlin's class-backend lambda
     * classes, CGLIB/Spring proxies, reflection accessors, dynamic proxies) so consumers can
     * de-emphasize them. Frames are kept, never dropped; callers only add the `method_synthetic`
     * field when this returns true.
     *
     * `method_synthetic` (not the common `synthetic` field) is the java-specific frame flag: on the
     * server, frame-level `synthetic` means "this frame was constructed by the SDK", which is a
     * different claim from "the compiler generated this method".
     */
    private fun isSyntheticFrame(
        className: String,
        methodName: String,
    ): Boolean {
        // lambdas: javac's synthetic `lambda$...` method, Kotlin's indy-generated `foo$lambda$N`,
        // or the invokedynamic `$$Lambda` class (also the legacy D8 `-$$Lambda$Foo$hash` form)
        if (methodName.startsWith(LAMBDA_METHOD_PREFIX) ||
            methodName.contains(LAMBDA_METHOD_MARKER) ||
            className.contains(LAMBDA_CLASS_MARKER)
        ) {
            return true
        }

        // Kotlin's CLASS lambda backend (older Kotlin/AGP, or `-Xlambdas=class`) compiles a lambda
        // to a nested class like `ExampleKt$run$1` (or `ExampleKt$main$1$2`) whose body is the
        // `invoke`/`invokeSuspend` bridge. The method guard is deliberate: a plain anonymous class
        // (`MyClass$1` with `run`) holds user-written code and must stay unmarked.
        if (methodName in KOTLIN_LAMBDA_METHOD_NAMES &&
            KOTLIN_LAMBDA_CLASS_SUFFIX_REGEX.containsMatchIn(className)
        ) {
            return true
        }

        // Android D8/R8 desugaring and outlining, which is the common shape on the Android runtime:
        // `Foo$$ExternalSyntheticLambda0`, `Foo$$InternalSyntheticLambda$..`, `$$ExternalSyntheticOutline0`
        DESUGARED_CLASS_MARKERS.forEach { marker ->
            if (className.contains(marker)) {
                return true
            }
        }

        // Spring CGLIB generated subclasses / fast-class dispatchers
        CGLIB_CLASS_MARKERS.forEach { marker ->
            if (className.contains(marker)) {
                return true
            }
        }

        // reflection: generated accessor classes under the reflect internals
        val isReflectPackage =
            REFLECT_PACKAGE_PREFIXES.any { className.startsWith(it) }
        if (isReflectPackage &&
            REFLECT_ACCESSOR_MARKERS.any { className.contains(it) }
        ) {
            return true
        }

        // JDK dynamic proxies: `com.sun.proxy.$ProxyN` or a simple class name matching `$ProxyN`
        if (className.startsWith(DYNAMIC_PROXY_PACKAGE_PREFIX) ||
            DYNAMIC_PROXY_SIMPLE_NAME_REGEX.matches(className.substringAfterLast('.'))
        ) {
            return true
        }

        return false
    }

    @Suppress("DEPRECATION")
    private fun getThreadId(thread: Thread): Long = thread.id

    /**
     * Builds the `stacktrace` map for a single throwable.
     *
     * When a trace exceeds [MAX_FRAMES_PER_STACKTRACE] we keep the frames NEAREST THE CRASH — since
     * frames are emitted bottom-up that is the tail of the list.
     */
    private fun buildStackTrace(
        stackTraces: Array<StackTraceElement>,
        inAppIncludes: List<String>,
        inAppExcludes: List<String>,
        releaseIdentifier: String?,
    ): Map<String, Any> {
        if (stackTraces.isEmpty()) {
            return emptyMap()
        }

        // Emit frames bottom-up (canonical order): frames[0] is the outermost/entry point,
        // the last frame is the crash site. Java's native stackTrace is innermost-first, so reverse it.
        val ordered = stackTraces.reversed()
        // keep the crash-side frames (the tail) when trimming
        val trimmed =
            if (ordered.size > MAX_FRAMES_PER_STACKTRACE) {
                ordered.subList(ordered.size - MAX_FRAMES_PER_STACKTRACE, ordered.size)
            } else {
                ordered
            }

        val frames = mutableListOf<Map<String, Any>>()
        trimmed.forEach { frame ->
            val myFrame = mutableMapOf<String, Any>()

            myFrame["module"] = frame.className
            myFrame["function"] = frame.methodName
            myFrame["platform"] = "java"

            // add release identifier for symbolication
            if (!releaseIdentifier.isNullOrEmpty()) {
                myFrame["map_id"] = releaseIdentifier
            }

            if (frame.lineNumber >= 0) {
                myFrame["lineno"] = frame.lineNumber
            }

            val fileName = frame.fileName
            if (fileName?.isNotEmpty() == true) {
                myFrame["filename"] = fileName
            }

            myFrame["in_app"] = isInApp(frame.className, inAppIncludes, inAppExcludes)

            // only present when true; false is the implied default
            if (isSyntheticFrame(frame.className, frame.methodName)) {
                myFrame["method_synthetic"] = true
            }

            frames.add(myFrame)
        }

        if (frames.isEmpty()) {
            return emptyMap()
        }

        return mapOf(
            "frames" to frames,
            "type" to "raw",
        )
    }

    /**
     * Serializes one throwable into an `$exception_list` item.
     *
     * [exceptionId] is the item's 0-based position in `$exception_list` and is echoed into the
     * mechanism; it is null for a single-item list, where the ids carry no information. [parentId] is
     * set for chained/suppressed items to the id of the throwable this one hangs off.
     * [mechanismType] carries "chained"/"suppressed" for derived items, or the primary throwable's
     * own mechanism ("generic" or the [PostHogThrowable] override) for the first item.
     *
     * Note: `exception_id`/`parent_id` are emitted on the wire for parity with the other SDKs, but
     * PostHog's ingestion currently drops them — its mechanism schema does not model the ids yet, so
     * the chain relationships are not persisted until that server-side change lands.
     */
    private fun buildExceptionItem(
        throwable: Throwable,
        exceptionId: Int?,
        parentId: Int?,
        handled: Boolean?,
        mechanismType: String,
        relationshipSource: String?,
        threadId: Long,
        inAppIncludes: List<String>,
        inAppExcludes: List<String>,
        releaseIdentifier: String?,
    ): Map<String, Any> {
        val thePackage = throwable.javaClass.`package`
        val theClass = throwable.javaClass.name
        val className = if (thePackage != null) theClass.replace(thePackage.name + ".", "") else theClass
        val exceptionPackage = thePackage?.name

        val mechanism =
            mutableMapOf<String, Any>(
                "synthetic" to false,
                "type" to mechanismType,
            )
        handled?.let { mechanism["handled"] = it }
        relationshipSource?.let { mechanism["source"] = it }
        if (exceptionId != null) {
            mechanism["exception_id"] = exceptionId
        }
        if (parentId != null) {
            mechanism["parent_id"] = parentId
        }

        val exception =
            mutableMapOf<String, Any>(
                "type" to className,
                "value" to throwable.message.orEmpty(),
                "mechanism" to mechanism,
                "thread_id" to threadId,
            )

        if (exceptionPackage?.isNotEmpty() == true) {
            exception["module"] = exceptionPackage
        }

        val stackTrace = buildStackTrace(throwable.stackTrace, inAppIncludes, inAppExcludes, releaseIdentifier)
        if (stackTrace.isNotEmpty()) {
            exception["stacktrace"] = stackTrace
        }

        return exception
    }

    // `inAppExcludes` is appended after `releaseIdentifier` so existing positional Kotlin calls stay
    // valid. This is a `@PostHogInternal` entry point, so the JVM descriptor change is fine.
    public fun fromThrowableToPostHogProperties(
        throwable: Throwable,
        inAppIncludes: List<String> = listOf(),
        releaseIdentifier: String? = null,
        inAppExcludes: List<String> = listOf(),
    ): MutableMap<String, Any> {
        // shared with the suppressed-exception pass below, so a throwable that appears both in the
        // chain and as someone's suppressed exception is only serialized once
        val circularDetector: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())

        var handled = true
        var isFatal = false
        var mechanismType = "generic"
        var exceptionSource: String? = null

        var currentThrowable: Throwable? = throwable
        val threadId: Long

        if (throwable is PostHogThrowable) {
            handled = throwable.handled
            isFatal = throwable.isFatal
            mechanismType = throwable.mechanism
            exceptionSource = throwable.source
            currentThrowable = throwable.cause
            threadId = getThreadId(throwable.thread)
        } else {
            threadId = getThreadId(Thread.currentThread())
        }

        // Traversal order (deterministic):
        //   1. the primary cause chain, root-most last: [main, main.cause, main.cause.cause, ...]
        //   2. then, in that same order, each throwable's directly-suppressed exceptions appended
        //      after the chain (one level only — suppressed-of-suppressed is NOT recursed).
        // exception_id is the 0-based index in this final list; parent_id points at the throwable a
        // cause/suppressed item hangs off.
        val items = mutableListOf<ExceptionRef>()

        // primary cause chain (bounded and deduplicated by walkCauseChain): first item keeps the
        // primary mechanism type; the rest are "chained" with parent_id = the id of the item they
        // are the cause OF (the previous item).
        walkCauseChain(currentThrowable, circularDetector).forEach { link ->
            val index = items.size
            items.add(
                ExceptionRef(
                    throwable = link,
                    parentId = if (index == 0) null else index - 1,
                    mechanismType = if (index == 0) mechanismType else "chained",
                    relationshipSource = if (index == 0) null else "cause",
                ),
            )
        }

        // suppressed exceptions fill whatever capacity the chain left over, in chain order, each
        // attributed to its holder via parent_id and marked mechanism type "suppressed".
        val chainSize = items.size
        holders@ for (holderId in 0 until chainSize) {
            for (suppressed in items[holderId].throwable.suppressed) {
                if (items.size >= MAX_EXCEPTION_LIST_SIZE) {
                    break@holders
                }
                if (circularDetector.add(suppressed)) {
                    items.add(
                        ExceptionRef(
                            throwable = suppressed,
                            parentId = holderId,
                            mechanismType = "chained",
                            relationshipSource = "suppressed",
                        ),
                    )
                }
            }
        }

        val cappedExceptions =
            items.mapIndexed { index, ref ->
                buildExceptionItem(
                    throwable = ref.throwable,
                    exceptionId = index,
                    parentId = ref.parentId,
                    handled = if (index == 0) handled else null,
                    mechanismType = ref.mechanismType,
                    relationshipSource = ref.relationshipSource,
                    threadId = threadId,
                    inAppIncludes = inAppIncludes,
                    inAppExcludes = inAppExcludes,
                    releaseIdentifier = releaseIdentifier,
                )
            }

        val exceptionProperties =
            mutableMapOf<String, Any>(
                EXCEPTION_LEVEL_ATTRIBUTE to if (isFatal) EXCEPTION_LEVEL_FATAL else "error",
            )

        if (cappedExceptions.isNotEmpty()) {
            exceptionProperties["\$exception_list"] = cappedExceptions
        }
        exceptionSource?.let { exceptionProperties["\$exception_source"] = it }

        return exceptionProperties
    }

    /** A throwable collected during the walk, with its place in the chain, before serialization. */
    private class ExceptionRef(
        val throwable: Throwable,
        val parentId: Int?,
        val mechanismType: String,
        val relationshipSource: String?,
    )

    internal companion object {
        const val EXCEPTION_LEVEL_FATAL = "fatal"
        const val EXCEPTION_LEVEL_ATTRIBUTE = "\$exception_level"

        /**
         * The cause chain starting at [root], identity-deduplicated and capped at
         * [MAX_EXCEPTION_LIST_SIZE] items.
         *
         * The cap bounds the walk itself, so a pathological chain (very deep, or a `cause` getter
         * that mints a fresh throwable on every read and therefore slips past the identity guard)
         * costs at most the cap. Identity-based because a Throwable subclass with value equality
         * (e.g. a Kotlin data class) must not make two distinct instances collide and cut the walk
         * short. Every consumer of a cause chain must go through this walk, so what gets serialized
         * and what gets matched against `ignoredExceptionTypes` is the same chain.
         *
         * [seen] lets a caller share the dedup set with a follow-up traversal (the coercer reuses
         * it when collecting suppressed exceptions).
         */
        fun walkCauseChain(
            root: Throwable?,
            seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap()),
        ): Sequence<Throwable> =
            sequence {
                var current = root
                var walked = 0
                while (current != null && walked < MAX_EXCEPTION_LIST_SIZE && seen.add(current)) {
                    yield(current)
                    walked++
                    current = current.cause
                }
            }

        // Max number of items serialized into `$exception_list`. Bounds the traversal itself: the
        // cause walk stops here, keeping the earliest (primary + nearest-cause) items, and only then
        // do suppressed exceptions fill any leftover capacity.
        const val MAX_EXCEPTION_LIST_SIZE = 50

        // Max frames per stacktrace; excess is dropped keeping the frames nearest the crash.
        const val MAX_FRAMES_PER_STACKTRACE = 64

        // Synthetic-frame heuristics.
        private const val LAMBDA_METHOD_PREFIX = "lambda$"
        private const val LAMBDA_METHOD_MARKER = "\$lambda\$"
        private const val LAMBDA_CLASS_MARKER = "\$\$Lambda"

        // Kotlin class-backend lambda: generated class name ending in a `$<digits>` segment, with
        // the lambda body compiled into `invoke` (or `invokeSuspend` for the coroutine variant).
        private val KOTLIN_LAMBDA_CLASS_SUFFIX_REGEX = Regex("\\\$\\d+\$")
        private val KOTLIN_LAMBDA_METHOD_NAMES =
            setOf(
                "invoke",
                "invokeSuspend",
            )
        private val DESUGARED_CLASS_MARKERS =
            listOf(
                "\$\$ExternalSynthetic",
                "\$\$InternalSynthetic",
            )
        private val CGLIB_CLASS_MARKERS =
            listOf(
                "\$\$FastClassBySpringCGLIB\$\$",
                "\$\$EnhancerBySpringCGLIB\$\$",
                "\$\$SpringCGLIB\$\$",
            )
        private val REFLECT_PACKAGE_PREFIXES =
            listOf(
                "jdk.internal.reflect.",
                "sun.reflect.",
            )
        private val REFLECT_ACCESSOR_MARKERS =
            listOf(
                "GeneratedMethodAccessor",
                "GeneratedConstructorAccessor",
            )
        private const val DYNAMIC_PROXY_PACKAGE_PREFIX = "com.sun.proxy."
        private val DYNAMIC_PROXY_SIMPLE_NAME_REGEX = Regex("\\\$Proxy\\d+")
    }
}
