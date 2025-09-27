async function checkChangelog() {
  // Check if skipped
  const skipChangelog =
    danger.github && (danger.github.pr.body + "").includes("#skip-changelog");

  if (skipChangelog) {
    return;
  }

  // Check if current PR has an entry in any changelog
  const changelogFiles = ["posthog/CHANGELOG.md", "posthog-android/CHANGELOG.md", "posthog-server/CHANGELOG.md"];
  let hasChangelogEntry = false;

  for (const file of changelogFiles) {
    try {
      const changelogContents = await danger.github.utils.fileContents(file);
      if (RegExp(`#${danger.github.pr.number}\\b`).test(changelogContents)) {
        hasChangelogEntry = true;
        break;
      }
    } catch (e) {
      // File doesn't exist, continue
    }
  }

  if (hasChangelogEntry) {
    return;
  }

  // Report missing changelog entry
  fail(
    "Please consider adding a changelog entry for the next release."
  );

  const prTitleFormatted = danger.github.pr.title
    .split(": ")
    .slice(-1)[0]
    .trim()
    .replace(/\.+$/, "");

  markdown(
    `
### Instructions and example for changelog
Please add an entry to the appropriate changelog:
- \`posthog/CHANGELOG.md\` (core module)
- \`posthog-android/CHANGELOG.md\` (android module)
- \`posthog-server/CHANGELOG.md\` (server module)

Make sure the entry includes this PR's number.
Example:
\`\`\`markdown
## Next
- ${prTitleFormatted} ([#${danger.github.pr.number}](${danger.github.pr.html_url}))
\`\`\`
If none of the above apply, you can opt out of this check by adding \`#skip-changelog\` to the PR description.`.trim()
  );
}

async function checkAll() {
  const isDraft = danger.github.pr.mergeable_state === "draft";

  if (isDraft) {
    return;
  }

  await checkChangelog();
}

schedule(checkAll);
