#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR/..

GITHUB_BRANCH="${1}"

if [[ $(git status) == *"nothing to commit"* ]]; then
    echo "Nothing to commit."
else
    echo "Going to push the changes."
    git config --global user.name 'PostHog Github Bot'
    git config --global user.email 'github-bot@posthog.com'
    git config pull.ff only
    git fetch
    git checkout ${GITHUB_BRANCH}
    git commit -am "Push changes"
    git merge --no-ff ${GITHUB_BRANCH}
    git push --set-upstream origin ${GITHUB_BRANCH}
fi
