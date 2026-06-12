#!/usr/bin/env bash

# Verifies that persisted state written by the current Android SDK does not break
# startup for older pinned SDK versions. Pin versions at meaningful persisted-state
# schema boundaries rather than testing the last N releases.
#
# Usage: DOWNGRADE_VERSION=<version> ./scripts/test-downgrade-compatibility.sh

set -euo pipefail

DOWNGRADE_VERSION="${1:-${DOWNGRADE_VERSION:-3.45.1}}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SMOKE_TEMPLATE_DIR="$SCRIPT_DIR/downgrade-compatibility-smoke"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/posthog-android-downgrade-compat.XXXXXX")"

cleanup() {
    if [ "${KEEP_DOWNGRADE_COMPAT_TMP:-}" = "1" ]; then
        echo "Keeping temp directory: $TMP_DIR"
    else
        rm -rf "$TMP_DIR"
    fi
}
trap cleanup EXIT

STATE_DIR="$TMP_DIR/state"
CURRENT_SMOKE_DIR="$TMP_DIR/current-smoke"
DOWNGRADED_SMOKE_DIR="$TMP_DIR/downgraded-smoke"
API_KEY="downgrade_compatibility_project"
CURRENT_VERSION="$(awk -F= '/^androidVersion=/ { print $2; exit }' "$REPO_ROOT/gradle.properties")"

validate_version() {
    local version="$1"
    if [[ -z "$version" || "$version" == -* || "$version" == *..* || ! "$version" =~ ^[A-Za-z0-9._+%-]+$ ]]; then
        echo "Invalid downgrade version: $version" >&2
        exit 64
    fi
}

create_smoke_project() {
    local project_dir="$1"
    mkdir -p "$project_dir"
    cp -R "$SMOKE_TEMPLATE_DIR/." "$project_dir"
}

run_smoke() {
    local project_dir="$1"
    local mode="$2"
    local dependency_mode="$3"
    local version="$4"

    (
        cd "$REPO_ROOT"
        ./gradlew \
            --project-dir "$project_dir" \
            --no-daemon \
            --no-parallel \
            --rerun-tasks \
            -PposthogDependencyMode="$dependency_mode" \
            -PposthogDependencyPath="$REPO_ROOT" \
            -PposthogVersion="$version" \
            -Dposthog.smoke.mode="$mode" \
            -Dposthog.smoke.stateDir="$STATE_DIR" \
            -Dposthog.smoke.apiKey="$API_KEY" \
            testReleaseUnitTest \
            --tests "com.posthog.downgrade.DowngradeCompatibilitySmokeTest"
    )
}

count_files() {
    local directory="$1"
    if [ ! -d "$directory" ]; then
        echo 0
        return
    fi
    find "$directory" -maxdepth 1 -type f | wc -l | tr -d ' '
}

require_positive_count() {
    local description="$1"
    local count="$2"
    if [ "$count" -eq 0 ]; then
        echo "Expected $description to be persisted, but none were found. State tree:" >&2
        find "$STATE_DIR" -maxdepth 8 -print | sort >&2 || true
        exit 1
    fi
}

validate_version "$DOWNGRADE_VERSION"
mkdir -p "$STATE_DIR"

create_smoke_project "$CURRENT_SMOKE_DIR"
echo "Writing persisted SDK state with current checkout ($CURRENT_VERSION)"
run_smoke "$CURRENT_SMOKE_DIR" write composite "$CURRENT_VERSION"

EVENT_QUEUE_FILE_COUNT="$(count_files "$STATE_DIR/events/$API_KEY")"
REPLAY_QUEUE_FILE_COUNT="$(count_files "$STATE_DIR/replay/$API_KEY")"
LOGS_QUEUE_FILE_COUNT="$(count_files "$STATE_DIR/logs/$API_KEY")"

require_positive_count "queued analytics event files" "$EVENT_QUEUE_FILE_COUNT"
require_positive_count "queued replay snapshot files" "$REPLAY_QUEUE_FILE_COUNT"
require_positive_count "queued log files" "$LOGS_QUEUE_FILE_COUNT"
require_positive_count "preference storage" "$(count_files "$STATE_DIR")"

echo "Current SDK persisted $EVENT_QUEUE_FILE_COUNT analytics event file(s), $REPLAY_QUEUE_FILE_COUNT replay snapshot file(s), and $LOGS_QUEUE_FILE_COUNT log file(s)."

create_smoke_project "$DOWNGRADED_SMOKE_DIR"
echo "Starting downgraded SDK ($DOWNGRADE_VERSION) against current SDK state"
run_smoke "$DOWNGRADED_SMOKE_DIR" read maven "$DOWNGRADE_VERSION"

echo "Downgrade compatibility smoke test passed for $DOWNGRADE_VERSION"
