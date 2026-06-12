# Keep the public delegate so customers can reference it after R8.
-keep class com.posthog.android.surveys.compose.PostHogSurveysComposeDelegate { *; }

# Compose runtime is kept by AGP's default rules + Compose's own consumer rules.
# Internal classes have no reflective access and are safe to obfuscate.
