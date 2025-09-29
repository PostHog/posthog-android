#!/bin/bash

set -eux

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

MODULE="$1"
NEW_VERSION="$2"

# Generate module-specific tag
case "$MODULE" in
  "core")
    TAG_NAME="core-v$NEW_VERSION"
    ;;
  "android")
    TAG_NAME="android-v$NEW_VERSION"
    ;;
  "server")
    TAG_NAME="server-v$NEW_VERSION"
    ;;
  *)
    echo "Usage: $0 {core|android|server} <version>"
    exit 1
    ;;
esac

git tag -a ${TAG_NAME} -m "$MODULE $NEW_VERSION"
git push && git push --tags
