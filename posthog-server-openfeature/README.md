# posthog-server-openfeature

An [OpenFeature](https://openfeature.dev) provider for the PostHog server-side JVM SDK (`posthog-server`). It requires Java 11 or later, because the OpenFeature Java SDK requires it.

```java
PostHogInterface posthog = PostHog.with(
    PostHogConfig.builder("<ph_project_api_key>")
        .host("https://us.i.posthog.com")
        .build()
);

OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new PostHogProvider(posthog));

Client client = api.getClient();
EvaluationContext context = new ImmutableContext("user-distinct-id", Map.of(
    "email", new Value("user@example.com"),
    "groups", new Value(new ImmutableStructure(Map.of("company", new Value("acme"))))
));

boolean enabled = client.getBooleanValue("my-flag", false, context);
```

You own the PostHog client: close it yourself when your application stops. The provider does not close it.

## Evaluation context

| OpenFeature | PostHog |
| --- | --- |
| `targetingKey` | distinct ID |
| `groups` attribute (structure of group type to group key) | groups |
| `groupProperties` attribute (structure of group type to properties) | group properties |
| all other attributes | person properties |

If the context has no targeting key, the evaluation returns the `TARGETING_KEY_MISSING` error. To use a fixed distinct ID for anonymous evaluations, use `new PostHogProvider(posthog, "anonymous")`.

## Flag types

| OpenFeature type | Value |
| --- | --- |
| boolean | `true` when the flag is enabled |
| string | the variant key |
| integer, long, double | the variant key parsed as a number (decimal, or hexadecimal like `0x10`) |
| object | the flag payload, which must be a JSON object or array |

When the flag is off for the user, the string, number and object evaluations return your default value with the `DEFAULT` reason. When the flag is on but its value does not match the requested type, the evaluation returns the `TYPE_MISMATCH` error. An unknown flag returns the `FLAG_NOT_FOUND` error.

Each evaluation captures a `$feature_flag_called` event, unless you set `sendFeatureFlagEvent(false)` on the `PostHogConfig`.
