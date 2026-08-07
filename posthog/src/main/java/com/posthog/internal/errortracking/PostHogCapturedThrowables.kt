package com.posthog.internal.errortracking

import com.posthog.PostHogInternal
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Process-wide guard that lets independent error-capture paths avoid double-reporting the very same
 * [Throwable] instance. The guard is directional: log-mirror paths (the Logback appender) consult it
 * and skip instances already reported, while the uncaught-exception handler only marks — a crash is
 * always captured as the authoritative fatal/unhandled record, even if the same instance was logged
 * first, and marking it prevents post-crash log mirrors from reporting it again.
 *
 * Membership is keyed strictly on **instance identity** (reference equality, not `equals`/`hashCode`
 * — a `Throwable` subclass with value equality must not make two distinct instances collide) and
 * held weakly, so entries disappear once the throwable is otherwise unreachable: the guard never
 * keeps a throwable (or its stack) alive.
 *
 * Not part of the public API; visible only because of the multi-module architecture.
 */
@PostHogInternal
public object PostHogCapturedThrowables {
    private val queue = ReferenceQueue<Throwable>()

    // Identity keys of throwables already captured. HashSet is not thread-safe, so all access is
    // synchronized on the set. Cleared entries are pruned opportunistically via [queue].
    private val seen = HashSet<IdentityWeakKey>()

    /**
     * Records [throwable] as captured and reports whether the caller should capture it.
     *
     * @return true if this is the first time the instance has been marked (the caller should
     *   capture it), false if it was already marked (the caller should skip it).
     */
    public fun markAndCheck(throwable: Throwable): Boolean =
        synchronized(seen) {
            pruneCleared()
            seen.add(IdentityWeakKey(throwable, queue))
        }

    // Drop keys whose referent has been collected. Must be called while holding the lock.
    private fun pruneCleared() {
        while (true) {
            val cleared = queue.poll() ?: break
            seen.remove(cleared)
        }
    }

    // Weak reference whose identity is the referential identity of the referent, so distinct
    // instances never collide even if their class overrides equals/hashCode. hashCode is captured
    // eagerly (identity hash is stable) so a key still matches after its referent is cleared.
    private class IdentityWeakKey(
        referent: Throwable,
        queue: ReferenceQueue<Throwable>,
    ) : WeakReference<Throwable>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakKey) return false
            val self = get()
            // Reference equality on the referents; a cleared referent only matches itself (handled
            // by the identity check above), which is fine — such keys are pruned via the queue.
            return self != null && self === other.get()
        }
    }
}
