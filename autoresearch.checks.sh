#!/bin/bash
set -euo pipefail

# Compile the posthog module to catch syntax/type errors
./gradlew :posthog:compileKotlin --no-daemon --quiet 2>&1 | tail -10

# Run correctness tests for the differ
./gradlew :posthog:test --tests "com.posthog.internal.replay.RRWireframeDifferTest" --no-daemon --quiet 2>&1 | tail -10
