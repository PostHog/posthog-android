#!/bin/bash

# ./scripts/bump-version.sh <module> <new version>
# eg ./scripts/bump-version.sh core "3.0.1"
# eg ./scripts/bump-version.sh android "3.0.2"

set -eux

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

MODULE="$1"
NEW_VERSION="$2"

GRADLE_FILEPATH="gradle.properties"

# Replace module-specific version
case "$MODULE" in
  "core")
    perl -pi -e "s/coreVersion=.*/coreVersion=$NEW_VERSION/g" $GRADLE_FILEPATH
    ;;
  "android")
    perl -pi -e "s/androidVersion=.*/androidVersion=$NEW_VERSION/g" $GRADLE_FILEPATH
    ;;
  "server")
    perl -pi -e "s/serverVersion=.*/serverVersion=$NEW_VERSION/g" $GRADLE_FILEPATH
    ;;
  *)
    echo "Usage: $0 {core|android|server} <version>"
    echo "Examples:"
    echo "  $0 core 3.0.1"
    echo "  $0 android 3.0.2"
    echo "  $0 server 1.0.1"
    exit 1
    ;;
esac
