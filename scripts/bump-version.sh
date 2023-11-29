#!/bin/bash

# ./scripts/bump-version.sh <new version>
# eg ./scripts/bump-version.sh "3.0.0-alpha.1"

set -eux

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

NEW_VERSION="$1"

GRADLE_FILEPATH="gradle.properties"

# Replace `versionName` with the given version
VERSION_NAME_PATTERN="versionName"
perl -pi -e "s/$VERSION_NAME_PATTERN=.*$/$VERSION_NAME_PATTERN=$NEW_VERSION/g" $GRADLE_FILEPATH
