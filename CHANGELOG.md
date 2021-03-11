## 1.1.2 - 2021-03-11

- Fix NullPointerException in PostHogActivityLifecycleCallbacks
- Refactor properties

## 1.1.1 - 2020-07-09

- Fix a bug where the name of the event changed to `$screen` instead of the key for the event screen

## 1.1.0 - 2020-07-08

- Use `$screen_name` instead of `$screen` as key for what screen you are on when sending a .screen event

## 1.0.2 - 2020-05-20

- Use `.defaultOptions(new Options().putContext("$lib", "custom-lib"))` to pass a default context

## 1.0.1 - 2020-05-20

- Added `.getAnonymousId()` to `PostHog`

## 1.0.0 - 2020-04-29

- First version
