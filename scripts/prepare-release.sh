#!/bin/bash

# ./scripts/prepare-release.sh <module> <new version>
# eg ./scripts/prepare-release.sh core "3.0.1"
# eg ./scripts/prepare-release.sh android "3.0.2"

set -eux

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

MODULE="$1"
NEW_VERSION="$2"
BRANCH_NAME="release/${MODULE}-v${NEW_VERSION}"

# ensure we're on main and up to date
git checkout main
git pull

# create release branch
git checkout -b "$BRANCH_NAME"

# bump version
./scripts/bump-version.sh $MODULE $NEW_VERSION

# commit and push release branch
git commit -am "chore(release): bump ${MODULE} to ${NEW_VERSION}"
git push -u origin "$BRANCH_NAME"

PR_URL="https://github.com/PostHog/posthog-android/compare/main...release%2F${MODULE}-v${NEW_VERSION}?expand=1"

echo ""
echo "Done! Created release branch: $BRANCH_NAME"
echo ""
echo "Next steps:"
echo "  1. Create a PR: $PR_URL"
echo "  2. Get approval and merge the PR"
echo "  3. After merge, create and push the tag:"
echo "     git checkout main && git pull"
echo "     git tag -a ${MODULE}-v${NEW_VERSION} -m \"${MODULE} ${NEW_VERSION}\""
echo "     git push --tags"
echo "  4. Create a GitHub release with the tag to trigger deployment"
