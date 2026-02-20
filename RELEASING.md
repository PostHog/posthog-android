# Releasing

Releases are managed with [changesets](https://github.com/changesets/changesets).

## Creating a changeset

Before submitting a PR, create a changeset by running:

```bash
pnpm changeset
```

The CLI will prompt you to select the affected package(s) and the type of version bump (patch, minor, major).
A changeset file will be generated in the `.changeset/` directory — commit it with your PR.

## Release process

Add a `release` label to your PR. When the PR is merged to `main`, the release workflow will automatically:

1. Bump versions based on changesets
2. Update changelogs in each package subfolder (e.g. `posthog/CHANGELOG.md`, `posthog-android/CHANGELOG.md`)
3. Commit version updates directly to `main`
4. Publish packages to Maven Central
5. Create Git tags and GitHub releases

All of this happens automatically when the PR is merged — no manual tagging or release creation needed!

## Dependency order

Packages are released sequentially in the following order to respect transitive dependencies:

1. **posthog** (core) — must be released first
2. **posthog-android** — depends on posthog core
3. **posthog-server** — depends on posthog core
4. **posthog-android-gradle-plugin**

If `posthog-android` or `posthog-server` have pending changes, ensure `posthog` (core) is released first (or has no pending changes). The release workflow handles this by running packages sequentially with `max-parallel: 1`.

## Tag naming convention

Tags are created automatically by the release workflow:

- `core-v3.23.0` → posthog core module
- `android-v3.23.0` → posthog-android module
- `server-v1.0.1` → posthog-server module
- `androidPlugin-v1.0.1` → posthog-android-gradle-plugin module

## Rotating Sonatype User Token

The release workflow uses a Sonatype user token for authentication when publishing to Maven Central.

1. **Generate a new user token**:
   - Go to [Maven Central Repository](https://central.sonatype.com/usertoken)
   - Log in with PostHog credentials
   - Generate a new user token
   - Copy the username and password values

2. **Update GitHub org secrets**:
   - Request temporary access if needed
   - Go to [Org Settings > Secrets and variables > Actions](https://github.com/organizations/PostHog/settings/secrets/actions) — target the desired repository only
   - Update `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` from previous step

3. **Revoke the old token (previous owner)**:
   - Go to [Maven Central Repository](https://central.sonatype.com/usertoken)
   - Revoke any previous tokens used

```yaml
env:
   SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
   SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
```

These environment variables are used in [PostHogPublishConfig.kt](https://github.com/PostHog/posthog-android/blob/main/buildSrc/src/main/java/PostHogPublishConfig.kt):

```kotlin
val sonatypeUsername = System.getenv("SONATYPE_USERNAME")
val sonatypePassword = System.getenv("SONATYPE_PASSWORD")
```

## Rotating GPG

The release workflow uses a GPG key to sign artifacts when publishing to Maven Central.

1. **Generate a new GPG key**:
   - Follow [this](https://central.sonatype.org/publish/requirements/gpg/) tutorial
   - `gpg --full-generate-key`
   - Use your PostHog email
   - A strong password (save in your password manager)
   - Default key type: RSA and RSA
   - Length: 4096
   - Remove expiration
   - After creation, save the revocation certificate in your password manager
   - Upload the key to a [public server](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key)
   - `gpg --keyserver keys.openpgp.org --send-keys $ID`
   - Visit the keyserver URL and confirm email
   - Export the private key (ASCII armored) — `gpg --export-secret-keys --armor $ID`

2. **Update GitHub org secrets**:
   - Request temporary access if needed
   - Go to [Org Settings > Secrets and variables > Actions](https://github.com/organizations/PostHog/settings/secrets/actions) — target the desired repository only
   - Update `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` from previous step

3. **Revoke the old GPG key (previous owner)**:
   - Go to [GPG Keychain](https://gpgtools.org/)
   - Revoke the GPG key
   - Update the key to a public server after revoking

```yaml
env:
   GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
   GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

These environment variables are used in [PostHogPublishConfig.kt](https://github.com/PostHog/posthog-android/blob/main/buildSrc/src/main/java/PostHogPublishConfig.kt):

```kotlin
val privateKey = System.getenv("GPG_PRIVATE_KEY")
val password = System.getenv("GPG_PASSPHRASE")
```
