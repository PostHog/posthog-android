# Releasing

### Core module (posthog):

1. Update `posthog/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/bump-version.sh core 3.23.0`
   - This bumps version, commits, and pushes with tag `core-v3.23.0`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases)
5. Create release with tag `core-v3.23.0`
6. A GitHub action triggers `make releaseCore` automatically

### Android module (posthog-android):

1. Update `posthog-android/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/bump-version.sh android 3.23.0`
   - This bumps version, commits, and pushes with tag `android-v3.23.0`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases)
5. Create release with tag `android-v3.23.0`
6. A GitHub action triggers `make releaseAndroid` automatically

## Tag Naming Convention

- `core-v3.23.0` → releases only posthog core module
- `android-v3.23.0` → releases only posthog-android module

Preview releases follow the pattern `core-v3.0.0-alpha.1`, `android-v3.0.0-beta.1`, etc.
