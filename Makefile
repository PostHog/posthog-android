.PHONY: clean compile stop checkFormat format api dryRelease release testReport test testJava generateLintBaseLine checkRelease updateLocks

clean:
	./gradlew clean

compile:
	./gradlew build :posthog-android-gradle-plugin:build

# We stop gradle at the end to make sure the cache folders
# don't contain any lock files and are free to be cached.
stop:
	./gradlew --stop

checkFormat:
	./gradlew spotlessCheck

format:
	./gradlew spotlessApply

api:
	./gradlew apiDump

dryRelease:
	./gradlew publishToMavenLocal

dryReleaseCore:
	./gradlew :posthog:publishToMavenLocal

dryReleaseAndroid:
	./gradlew :posthog-android:publishToMavenLocal

dryReleaseSurveysCompose:
	./gradlew :posthog-android-surveys-compose:publishToMavenLocal

dryReleaseServer:
	./gradlew :posthog-server:publishToMavenLocal

dryReleaseAndroidPlugin:
	./gradlew :posthog-android-gradle-plugin:publishToMavenLocal

release:
	./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository

releaseCore:
	./gradlew :posthog:publishToSonatype closeAndReleaseSonatypeStagingRepository

releaseAndroid:
	./gradlew :posthog-android:publishToSonatype closeAndReleaseSonatypeStagingRepository

releaseSurveysCompose:
	./gradlew :posthog-android-surveys-compose:publishToSonatype closeAndReleaseSonatypeStagingRepository

releaseServer:
	./gradlew :posthog-server:publishToSonatype closeAndReleaseSonatypeStagingRepository

releaseAndroidPlugin:
	./gradlew :posthog-android-gradle-plugin:publishToSonatype :posthog-android-gradle-plugin:closeAndReleaseSonatypeStagingRepository

testReport:
	./gradlew koverHtmlReport

# compile already runs the tests (tests java and android)
test:
	./gradlew testDebugUnitTest

# compile already runs the tests (tests only java)
testJava:
	./gradlew :posthog:test

generateLintBaseLine:
	rm -f posthog-android/lint-baseline.xml
	./gradlew lintDebug -Dlint.baselines.continue=true

# Verify release tasks succeed and committed dependency locks are complete
checkRelease:
	CI=false ./gradlew publishToMavenLocal :posthog-android-gradle-plugin:publishToMavenLocal --write-locks
	git diff --exit-code -- ':(glob)**/gradle.lockfile'
	@test -z "$$(git status --porcelain --untracked-files=all -- ':(glob)**/gradle.lockfile')" || \
		(git status --short --untracked-files=all -- ':(glob)**/gradle.lockfile'; exit 1)

# Regenerate gradle.lockfile for all build and publishing configurations
updateLocks:
	./gradlew build :posthog-android-gradle-plugin:build --write-locks
	CI=false ./gradlew publishToMavenLocal :posthog-android-gradle-plugin:publishToMavenLocal --write-locks
