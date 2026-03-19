#!/bin/bash
set -euo pipefail

# Run core JVM tests to ensure no regressions
./gradlew :posthog:test --no-daemon --quiet 2>&1 | tail -10

# Check formatting
./gradlew spotlessCheck --no-daemon --quiet 2>&1 | tail -10
