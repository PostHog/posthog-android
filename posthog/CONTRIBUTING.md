# Contributing

If you would like to contribute code to `posthog` (core package) you can do so through
GitHub by forking the repository and opening a pull request against `main`.

When submitting code, please make every effort to follow existing conventions
and style in order to keep the code as readable as possible. Please also make
sure your code compiles by running `make compile`. In addition please consider adding
unit tests covering your change, this will make your change much more likely to be accepted

## Development guide

1. Install Java 17 - `brew install openjdk@17`
2. Make sure you have the Android SDK installed
3. Run tests with `./gradlew :posthog:test`
4. Run the build with `./gradlew :posthog:build`
5. The core package contains the main PostHog SDK logic without Android-specific dependencies

Above all, thank you for contributing!