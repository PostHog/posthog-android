# Releasing

### Core module (posthog):

1. Update `posthog/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/bump-version.sh core 3.23.0`
   - This bumps version, commits, and pushes with tag `core-v3.23.0`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases) and draft a new release
5. Choose a release name (e.g. `core-v3.23.0`), and the tag you just created, ideally the same.
6. Write a description of the release.
7. Publish the release.
8. A GitHub action triggers `make releaseAndroid` automatically
9. Done.

### Android module (posthog-android):

1. Update `posthog-android/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/bump-version.sh android 3.23.0`
   - This bumps version, commits, and pushes with tag `android-v3.23.0`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases) and draft a new release
5. Choose a release name (e.g. `android-v3.23.0`), and the tag you just created, ideally the same.
6. Write a description of the release.
7. Publish the release.
8. A GitHub action triggers `make releaseAndroid` automatically
9. Done.

### Server module (posthog-server):

1. Update `posthog-server/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/bump-version.sh server 1.0.1`
   - This bumps version, commits, and pushes with tag `server-v1.0.1`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases) and draft a new release
5. Choose a release name (e.g. `server-v1.0.1`), and the tag you just created, ideally the same.
6. Write a description of the release.
7. Publish the release.
8. A GitHub action triggers `make releaseServer` automatically
9. Done.

## Tag Naming Convention

- `core-v3.23.0` → releases only posthog core module
- `android-v3.23.0` → releases only posthog-android module
- `server-v1.0.1` → releases only posthog-server module

Preview releases follow the pattern `core-v3.0.0-alpha.1`, `android-v3.0.0-beta.1`, `server-v1.0.0-alpha.1`, etc.
