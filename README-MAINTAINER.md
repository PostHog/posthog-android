# Maintainer Guide - PostHog Android SDK

This document contains information specifically for maintainers of the PostHog Android SDK.

## Rotating Sonatype User Token

The release workflow uses a Sonatype user token for authentication when publishing to Maven Central.


1. **Generate a new user token**:
   - Go to [Mavel Central Repository](https://central.sonatype.com/usertoken)
   - Log in with PostHog credentials
   - Generate a new user token
   - Copy the username and password values

2. **Update GitHub repository secrets**:
   - Request temporary access if needed
   - Go to [Repository Settings > Secrets and variables > Actions](https://github.com/PostHog/posthog-android/settings/secrets/actions)
   - Update `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` from previous step

4. **Revoke the old token (previous owner)**:
   - Go to [Mavel Central Repository](https://central.sonatype.com/usertoken)
   - Revoke any previous tokens used


The user token is used in `.github/workflows/release.yml` as environment variables for Maven Central authentication:

```yaml
env:
      SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
      SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
```
