# Contributing

If you would like to contribute code to `posthog-android` you can do so through
GitHub by forking the repository and opening a pull request against `main`.

When submitting code, please make every effort to follow existing conventions
and style in order to keep the code as readable as possible. Please also make
sure your code compiles by running `make compile`. In addition please consider adding
unit tests covering your change, this will make your change much more likely to be accepted

## Development guide

1. Install Android Studio
2. Follow instructions here for setup https://developer.android.com/studio/run/emulator
3. Make sure you have Java 17 installed locally - `brew install openjdk@17`
4. Change the JDK in Android Studio to build using the correct version.
5. In the `posthog-android-sample` project, edit `MyApp.kt` and set the `apiKey` variable to your own PostHog Project API Key.
6. Select `posthog-android-sample` and the device on the top bar and click run

Above all, thank you for contributing!
