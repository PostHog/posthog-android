# PostHog Android

Please see the main [PostHog docs](https://posthog.com/docs).

Specifically, the [Android integration](https://posthog.com/docs/integrations/android-integration) details.

## Development guide

1. Install Android Studio
2. Follow instructions here for setup https://developer.android.com/studio/run/emulator
3. Make sure you have Java 8 installed locally - `brew tap adoptopenjdk/openjdk && brew install --cask adoptopenjdk8`
4. Change the JDK in IntelliJ to build using the correct version.
5. Select `posthog-sample` and the device on the top bar and click run

## How to run tests

1. Make sure you have Android Studio, it's set up correctly, and you have Java 8 installed locally.
2. Make sure your JDK matches the project's version and what you just installed (Java 8 adoptopenjdk). Go to "view -> tool windows -> gradle -> build tool settings -> gradle settings -> change gradle JDK version".
3. In the file navigation section, go to "Project Files -> Tests".
4. Right click on a test file to run it or use the toolbar on top.

## Questions?

### [Join our Slack community.](https://join.slack.com/t/posthogusers/shared_invite/enQtOTY0MzU5NjAwMDY3LTc2MWQ0OTZlNjhkODk3ZDI3NDVjMDE1YjgxY2I4ZjI4MzJhZmVmNjJkN2NmMGJmMzc2N2U3Yjc3ZjI5NGFlZDQ)
