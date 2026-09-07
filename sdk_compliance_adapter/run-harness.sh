#!/usr/bin/env bash
set -euo pipefail
adapter_port=${1:-18290}
mock_port=${2:-19290}
mkdir -p report
# Linux host networking also reaches an adb-forwarded Android listener.
docker run --rm --network host \
  -v "$PWD/report:/report" \
  ghcr.io/posthog/sdk-test-harness:1.0.0 \
  run --adapter-url "http://127.0.0.1:$adapter_port" \
  --mock-port "$mock_port" --mock-url "http://127.0.0.1:$mock_port" \
  --sdk-type server --concurrency 1 --report /report/compliance.json \
  2>&1 | tee report/harness.log
