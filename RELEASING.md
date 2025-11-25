# Releasing

### Core module (posthog):

1. Update `posthog/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/prepare-release.sh core 3.23.0`
   - This bumps version, commits, and pushes with tag `core-v3.23.0`
4. Go to [GH Releases](https://github.com/PostHog/posthog-android/releases) and draft a new release
5. Choose a release name (e.g. `core-v3.23.0`), and the tag you just created, ideally the same.
6. Write a description of the release.
7. Publish the release.
8. A GitHub action triggers `make releaseCore` automatically
9. Done.

### Android module (posthog-android):

1. Update `posthog-android/CHANGELOG.md` with the version and date
2. Commit the changes
3. Run: `./scripts/prepare-release.sh android 3.23.0`
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
3. Run: `./scripts/prepare-release.sh server 1.0.1`
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

# Rotating Sonatype User Token

The release workflow uses a Sonatype user token for authentication when publishing to Maven Central.


1. **Generate a new user token**:
   - Go to [Mavel Central Repository](https://central.sonatype.com/usertoken)
   - Log in with PostHog credentials
   - Generate a new user token
   - Copy the username and password values

2. **Update GitHub org secrets**:
   - Request temporary access if needed
   - Go to [Org Settings > Secrets and variables > Actions](https://github.com/organizations/PostHog/settings/secrets/actions) - target the desired repository only
   - Update `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` from previous step

4. **Revoke the old token (previous owner)**:
   - Go to [Mavel Central Repository](https://central.sonatype.com/usertoken)
   - Revoke any previous tokens used


The user token is used in [release.yml](https://github.com/PostHog/posthog-android/blob/743a341365f5d9c1cf254a7b01882b59c3089e30/.github/workflows/release.yml#L25-L26) as environment variables for Maven Central authentication:

```yaml
env:
   SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
   SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
```

These environment variables are then read and used in [PostHogPublishConfig.kt](https://github.com/PostHog/posthog-android/blob/743a341365f5d9c1cf254a7b01882b59c3089e30/buildSrc/src/main/java/PostHogPublishConfig.kt#L128-L129):

```kotlin
val sonatypeUsername = System.getenv("SONATYPE_USERNAME")
val sonatypePassword = System.getenv("SONATYPE_PASSWORD")
```

# Rotating GPG

The release workflow uses a GPG key to sign artifacts when publishing to Maven Central.

1. **Generate a new GPG key**:
   - Install and use [GPG Keychain](https://gpgtools.org/)
   - Use your PostHog email
   - A strong password (save in your password manager)
   - Default key type: RSA and RSA
   - Length: 4096
   - Remove expiration
   - Upload the key to a public server after creation
  
2. **Update GitHub org secrets**:
   - Request temporary access if needed
   - Go to [Org Settings > Secrets and variables > Actions](https://github.com/organizations/PostHog/settings/secrets/actions) - target the desired repository only
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

These environment variables are then read and used in [PostHogPublishConfig.kt](https://github.com/PostHog/posthog-android/blob/743a341365f5d9c1cf254a7b01882b59c3089e30/buildSrc/src/main/java/PostHogPublishConfig.kt#L115-L116):

```kotlin
val privateKey = System.getenv("GPG_PRIVATE_KEY")
val password = System.getenv("GPG_PASSPHRASE")
```
