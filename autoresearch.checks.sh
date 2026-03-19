#!/bin/bash
set -euo pipefail

# Compile the posthog module to catch syntax/type errors
./gradlew :posthog:compileKotlin --no-daemon --quiet 2>&1 | tail -10
