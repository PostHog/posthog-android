package com.posthog.internal.errortracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ThrowableCoercerTest {
    private val coercer = ThrowableCoercer()

    private fun frame(
        className: String,
        methodName: String,
        line: Int = 10,
        file: String? = "Sample.java",
    ) = StackTraceElement(className, methodName, file, line)

    private fun throwableWith(
        message: String,
        frames: Array<StackTraceElement>,
        cause: Throwable? = null,
    ): Throwable {
        val t = if (cause != null) RuntimeException(message, cause) else RuntimeException(message)
        t.stackTrace = frames
        return t
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.exceptionList(): List<Map<String, Any>> = this["\$exception_list"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.mechanism(): Map<String, Any> = this["mechanism"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.frames(): List<Map<String, Any>> =
        (this["stacktrace"] as Map<String, Any>)["frames"] as List<Map<String, Any>>

    @Test
    fun `emits required linkage for a single exception`() {
        val t = throwableWith("boom", arrayOf(frame("com.app.Only", "only")))

        val list = coercer.fromThrowableToPostHogProperties(t).exceptionList()
        assertEquals(1, list.size)

        val mechanism = list[0].mechanism()
        assertEquals(0, (mechanism["exception_id"] as Number).toInt())
        assertFalse(mechanism.containsKey("parent_id"))
        assertEquals("generic", mechanism["type"])
    }

    @Test
    fun `exception_id parent_id and chained type across a 3-deep cause chain`() {
        val root = throwableWith("root", arrayOf(frame("com.app.Root", "root")))
        val middle = throwableWith("middle", arrayOf(frame("com.app.Mid", "mid")), cause = root)
        val top = throwableWith("top", arrayOf(frame("com.app.Top", "top")), cause = middle)

        val list = coercer.fromThrowableToPostHogProperties(top).exceptionList()
        assertEquals(3, list.size)

        // item 0: the primary throwable, generic mechanism, no parent
        assertEquals("top", list[0]["value"])
        assertEquals(0, (list[0].mechanism()["exception_id"] as Number).toInt())
        assertEquals("generic", list[0].mechanism()["type"])
        assertFalse(list[0].mechanism().containsKey("parent_id"))

        // item 1: cause of item 0
        assertEquals("middle", list[1]["value"])
        assertEquals(1, (list[1].mechanism()["exception_id"] as Number).toInt())
        assertEquals("chained", list[1].mechanism()["type"])
        assertEquals("cause", list[1].mechanism()["source"])
        assertEquals(0, (list[1].mechanism()["parent_id"] as Number).toInt())
        assertFalse(list[1].mechanism().containsKey("handled"))

        // item 2: cause of item 1
        assertEquals("root", list[2]["value"])
        assertEquals(2, (list[2].mechanism()["exception_id"] as Number).toInt())
        assertEquals("chained", list[2].mechanism()["type"])
        assertEquals("cause", list[2].mechanism()["source"])
        assertEquals(1, (list[2].mechanism()["parent_id"] as Number).toInt())
    }

    @Test
    fun `serializes suppressed exceptions after the chain with suppressed mechanism`() {
        val top = throwableWith("top", arrayOf(frame("com.app.Top", "top")))
        val suppressed = throwableWith("suppressed", arrayOf(frame("com.app.Sup", "sup")))
        top.addSuppressed(suppressed)

        val list = coercer.fromThrowableToPostHogProperties(top).exceptionList()
        assertEquals(2, list.size)

        // suppressed item is appended after the chain, attributed to its holder (id 0)
        assertEquals("suppressed", list[1]["value"])
        assertEquals("chained", list[1].mechanism()["type"])
        assertEquals("suppressed", list[1].mechanism()["source"])
        assertEquals(1, (list[1].mechanism()["exception_id"] as Number).toInt())
        assertEquals(0, (list[1].mechanism()["parent_id"] as Number).toInt())
    }

    @Test
    fun `keeps distinct suppressed exceptions that are equal by value`() {
        // The circular-reference guard is identity-based, so a Throwable subclass with value
        // equality must not make one of two distinct instances vanish from the list.
        val top = throwableWith("top", arrayOf(frame("com.app.Top", "top")))
        top.addSuppressed(ValueEqualThrowable("same"))
        top.addSuppressed(ValueEqualThrowable("same"))

        val list = coercer.fromThrowableToPostHogProperties(top).exceptionList()
        assertEquals(3, list.size)
        assertEquals("suppressed", list[1].mechanism()["source"])
        assertEquals("suppressed", list[2].mechanism()["source"])
    }

    @Test
    fun `caps exception list keeping the earliest root-most items`() {
        // build a chain deeper than the cap
        val depth = ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE + 10
        var current: Throwable = throwableWith("e0", arrayOf(frame("com.app.C0", "m")))
        for (i in 1 until depth) {
            current = throwableWith("e$i", arrayOf(frame("com.app.C$i", "m")), cause = current)
        }

        val list = coercer.fromThrowableToPostHogProperties(current).exceptionList()
        assertEquals(ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE, list.size)
        // the earliest (primary) item survives; the deepest causes are dropped
        assertEquals("e${depth - 1}", list.first()["value"])
        assertEquals(0, (list.first().mechanism()["exception_id"] as Number).toInt())
    }

    @Test
    fun `bounds the cause walk itself on an endless chain`() {
        // GrowingCauseThrowable mints a brand new cause on every read, so the identity guard can
        // never stop it: only a bounded walk terminates here at all.
        val reads = intArrayOf(0)
        val t = GrowingCauseThrowable(0, reads)

        val list = coercer.fromThrowableToPostHogProperties(t).exceptionList()

        assertEquals(ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE, list.size)
        // the walk must stop AT the cap rather than collect everything and slice afterwards
        assertTrue(
            reads[0] <= ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE + 1,
            "expected at most ${ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE + 1} cause reads, got ${reads[0]}",
        )
    }

    @Test
    fun `suppressed exceptions only fill the capacity the chain leaves over`() {
        val root = throwableWith("root", arrayOf(frame("com.app.Root", "root")))
        val top = throwableWith("top", arrayOf(frame("com.app.Top", "top")), cause = root)
        repeat(ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE * 2) { i ->
            top.addSuppressed(throwableWith("sup$i", arrayOf(frame("com.app.Sup$i", "s"))))
        }

        val list = coercer.fromThrowableToPostHogProperties(top).exceptionList()

        assertEquals(ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE, list.size)
        // 2 chain items first, then suppressed items attributed to the holder (id 0) fill the rest
        assertEquals("generic", list[0].mechanism()["type"])
        assertEquals("chained", list[1].mechanism()["type"])
        assertEquals("suppressed", list[2].mechanism()["source"])
        assertEquals(0, (list[2].mechanism()["parent_id"] as Number).toInt())
        assertEquals("suppressed", list.last().mechanism()["source"])
        assertEquals(
            ThrowableCoercer.MAX_EXCEPTION_LIST_SIZE - 1,
            (list.last().mechanism()["exception_id"] as Number).toInt(),
        )
    }

    @Test
    fun `caps frames per stacktrace keeping the crash-side frames`() {
        val total = ThrowableCoercer.MAX_FRAMES_PER_STACKTRACE + 20
        // JVM order: index 0 is the crash site. Name frames by distance from crash.
        val frames = Array(total) { i -> frame("com.app.F$i", "m$i", line = i) }
        val t = throwableWith("boom", frames)

        val emitted = coercer.fromThrowableToPostHogProperties(t).exceptionList()[0].frames()
        assertEquals(ThrowableCoercer.MAX_FRAMES_PER_STACKTRACE, emitted.size)
        // crash frame (F0) is nearest the crash and must survive as the LAST emitted frame
        assertEquals("com.app.F0", emitted.last()["module"])
        // the outermost retained frame is the one MAX_FRAMES-1 away from the crash
        assertEquals("com.app.F${ThrowableCoercer.MAX_FRAMES_PER_STACKTRACE - 1}", emitted.first()["module"])
    }

    @Test
    fun `marks synthetic frames via each heuristic`() {
        data class Case(val className: String, val methodName: String, val synthetic: Boolean, val label: String)

        val cases =
            listOf(
                // lambdas
                Case("com.app.Service", "lambda\$doWork\$0", true, "lambda method"),
                Case("com.app.Service\$\$Lambda\$14", "run", true, "lambda class"),
                Case("com.app.MainActivity", "onCreate\$lambda\$3", true, "kotlin indy lambda method"),
                // Android D8/R8 desugaring: the common shape on the Android runtime
                Case("com.app.MainActivity\$\$ExternalSyntheticLambda0", "onClick", true, "d8 desugared lambda"),
                Case("com.app.MainActivity\$\$InternalSyntheticLambda\$1\$abc\$0", "run", true, "d8 internal lambda"),
                Case("com.app.Repo\$\$ExternalSyntheticOutline0", "m", true, "d8 outlined method"),
                Case("com.app.-\$\$Lambda\$MainActivity\$abc123", "run", true, "legacy d8 lambda class"),
                // Kotlin's class lambda backend: `Foo$run$1` with the body in invoke/invokeSuspend
                Case("com.app.ExampleKt\$run\$1", "invoke", true, "kotlin class-backend lambda"),
                Case("com.app.ExampleKt\$main\$1\$2", "invokeSuspend", true, "nested kotlin coroutine lambda"),
                // Spring CGLIB
                Case("com.app.Bean\$\$FastClassBySpringCGLIB\$\$abc", "invoke", true, "spring fastclass"),
                Case("com.app.Bean\$\$EnhancerBySpringCGLIB\$\$abc", "doStuff", true, "spring enhancer"),
                Case("com.app.Bean\$\$SpringCGLIB\$\$0", "doStuff", true, "spring cglib"),
                // reflection accessors
                Case("jdk.internal.reflect.GeneratedMethodAccessor42", "invoke", true, "jdk generated method accessor"),
                Case("sun.reflect.GeneratedConstructorAccessor7", "newInstance", true, "sun generated ctor accessor"),
                // dynamic proxies
                Case("com.sun.proxy.\$Proxy23", "handle", true, "com.sun.proxy"),
                Case("com.app.\$Proxy99", "handle", true, "simple-name proxy"),
                // negatives
                Case("com.app.RealService", "doWork", false, "ordinary frame"),
                Case("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke", false, "plain reflect frame (not an accessor)"),
                Case("com.app.ProxyHelper", "handle", false, "class merely containing Proxy"),
                Case("com.app.SyntheticEventBuilder", "build", false, "class merely containing Synthetic"),
                Case("com.app.MyClass\$1", "run", false, "anonymous class body (not an invoke bridge)"),
                Case("com.app.MyClass\$1", "handleMessage", false, "anonymous class with a user method"),
            )

        cases.forEach { case ->
            val t = throwableWith("boom", arrayOf(frame(case.className, case.methodName)))
            val emitted = coercer.fromThrowableToPostHogProperties(t).exceptionList()[0].frames().first()
            if (case.synthetic) {
                assertEquals(true, emitted["method_synthetic"], "expected method_synthetic for ${case.label}")
            } else {
                // omitted (not false) when not synthetic
                assertFalse(emitted.containsKey("method_synthetic"), "expected no method_synthetic key for ${case.label}")
                assertNull(emitted["method_synthetic"])
            }
            // the compiler-generated flag must never be sent as the common frame-level `synthetic`
            // field, which the server reads as "SDK-constructed frame"
            assertFalse(emitted.containsKey("synthetic"), "must not emit frame `synthetic` for ${case.label}")
        }
    }

    @Test
    fun `in_app excludes beat includes`() {
        val t =
            throwableWith(
                "boom",
                arrayOf(
                    frame("com.app.feature.Included", "a"),
                    frame("com.app.feature.internal.Excluded", "b"),
                    frame("org.thirdparty.Lib", "c"),
                ),
            )

        val frames =
            coercer
                .fromThrowableToPostHogProperties(
                    t,
                    inAppIncludes = listOf("com.app"),
                    inAppExcludes = listOf("com.app.feature.internal"),
                ).exceptionList()[0]
                .frames()

        // frames are reversed; index by module for clarity
        val byModule = frames.associateBy { it["module"] as String }
        assertEquals(true, byModule["com.app.feature.Included"]!!["in_app"])
        // exclude wins even though it also matches the include prefix
        assertEquals(false, byModule["com.app.feature.internal.Excluded"]!!["in_app"])
        // outside includes => not in app
        assertEquals(false, byModule["org.thirdparty.Lib"]!!["in_app"])
    }

    /**
     * A cause chain with no end: every `cause` read returns a fresh instance, which defeats the
     * identity-based circular guard. Counts reads so a test can assert the walk is bounded.
     */
    private class GrowingCauseThrowable(
        private val depth: Int,
        private val reads: IntArray,
    ) : RuntimeException("depth-$depth") {
        override val cause: Throwable
            get() {
                reads[0]++
                return GrowingCauseThrowable(depth + 1, reads)
            }
    }

    private class ValueEqualThrowable(private val token: String) : RuntimeException(token) {
        override fun equals(other: Any?): Boolean = other is ValueEqualThrowable && other.token == token

        override fun hashCode(): Int = token.hashCode()
    }

    @Test
    fun `empty includes marks everything in_app by default`() {
        val t =
            throwableWith(
                "boom",
                arrayOf(
                    frame("com.app.A", "a"),
                    frame("org.thirdparty.B", "b"),
                ),
            )

        val frames = coercer.fromThrowableToPostHogProperties(t).exceptionList()[0].frames()
        assertTrue(frames.all { it["in_app"] == true })
    }
}
