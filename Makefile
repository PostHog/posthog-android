.PHONY: clean compile stop checkFormat format api dryRelease release testReport test testJava generateLintBaseLine

clean:
	./gradlew clean

compile:
	./gradlew build

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

release:
	./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository

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
