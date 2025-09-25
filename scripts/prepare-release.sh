#!/bin/bash

# ./scripts/prepare-release.sh <module> <new version>
# eg ./scripts/prepare-release.sh core "3.0.1"
# eg ./scripts/prepare-release.sh android "3.0.2"

set -eux

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

MODULE="$1"
NEW_VERSION="$2"

# bump version
./scripts/bump-version.sh $MODULE $NEW_VERSION

# commit changes
./scripts/commit-code.sh

# create and push tag
./scripts/create-tag.sh $MODULE $NEW_VERSION

echo "Done! Created tag ${MODULE}-v${NEW_VERSION}"
echo "Go create a GitHub release with this tag to trigger deployment."
