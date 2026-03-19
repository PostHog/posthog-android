#!/bin/bash
set -euo pipefail

# Quick syntax check (< 1s)
for f in posthog/src/main/java/com/posthog/internal/replay/RRWireframeDiffer.kt \
         posthog/src/test/java/com/posthog/internal/replay/RRWireframeDifferBenchmarkTest.kt; do
    if [ ! -f "$f" ]; then
        echo "ERROR: $f missing"
        exit 1
    fi
done

# Clean test results to force re-run (Gradle UP-TO-DATE check skips unchanged tests)
rm -rf posthog/build/test-results/test

# Run benchmark tests
if ! ./gradlew :posthog:test --tests "com.posthog.internal.replay.RRWireframeDifferBenchmarkTest" --no-daemon --quiet 2>&1; then
    echo "METRIC total_µs=0"
    exit 1
fi

# Extract METRIC lines from JUnit XML reports
REPORT_DIR="posthog/build/test-results/test"
if [ -d "$REPORT_DIR" ]; then
    grep -h "METRIC" "$REPORT_DIR"/*.xml 2>/dev/null | \
        sed 's/.*\(METRIC [^ <]*\).*/\1/' | \
        sort -u
fi
