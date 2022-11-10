Releasing
=========

 0. Install Java 8
 1. Follow the development instructions to get initially set up.
 2. Change the version in `gradle.properties` to a non-SNAPSHOT version.
 3. Update the `CHANGELOG.md` for the impending release.
 4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
 5. `git tag -a X.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
 6. `./gradlew clean uploadArchives`. If you're handling credentials in the command line you must pass in `-PNEXUS_USERNAME={sonatype username}` and `-PNEXUS_PASSWORD={sonatype_password}` (Note: account permission level must be allowed to deploy artifacts on groupId not just access to the repository manager). Follow the [gradle instructions](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials) for code signing using an RSA key. 
 7. Update the `gradle.properties` to the next SNAPSHOT version.
 8. `git commit -am "Prepare next development version."`
 9. `git push && git push --tags`
 10. Visit [Sonatype Nexus](https://oss.sonatype.org/) and promote the artifact.
