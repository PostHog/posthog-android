##---------------Begin: proguard configuration for Gson  ----------
# Gson uses generic type information stored in a class file when working with fields. Proguard
# removes such information by default, so configure it to keep all of it.
-keepattributes Signature

# For using GSON @Expose annotation
-keepattributes *Annotation*

# Gson specific classes
-dontwarn sun.misc.**
#-keep class com.google.gson.stream.** { *; }

# Application classes that will be serialized/deserialized over Gson
-keep class com.posthog.PostHogEvent { *; }
-keep class com.posthog.PostHogEvent { <init>(); }

-keep class com.posthog.internal.PostHogBatchEvent { *; }
-keep class com.posthog.internal.PostHogBatchEvent { <init>(); }

-keep class com.posthog.internal.PostHogFlagsRequest { *; }
-keep class com.posthog.internal.PostHogFlagsRequest { <init>(); }

-keep class com.posthog.internal.PostHogFlagsResponse { *; }
-keep class com.posthog.internal.PostHogFlagsResponse { <init>(); }

-keep class com.posthog.internal.FeatureFlag { *; }
-keep class com.posthog.internal.FeatureFlag { <init>(); }

-keep class com.posthog.internal.FeatureFlagMetadata { *; }
-keep class com.posthog.internal.FeatureFlagMetadata { <init>(); }

-keep class com.posthog.internal.EvaluationReason { *; }
-keep class com.posthog.internal.EvaluationReason { <init>(); }

-keep class com.posthog.internal.PostHogRemoteConfigResponse { *; }
-keep class com.posthog.internal.PostHogRemoteConfigResponse { <init>(); }

# Session Replay
-keep class com.posthog.internal.replay.** { *; }
-keep class com.posthog.internal.replay.** { <init>(); }

# Surveys
-keep class com.posthog.surveys.** { *; }
-keep class com.posthog.surveys.** { <init>(); }

# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Prevent R8 from leaving Data object members always null
-keepclassmembers,allowobfuscation class * {
 @com.google.gson.annotations.SerializedName <fields>;
}

# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

##---------------End: proguard configuration for Gson  ----------

##---------------Begin: proguard configuration for okhttp3  ----------
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
##---------------End: proguard configuration for okhttp3  ----------

##---------------Begin: proguard configuration for okhttp3  ----------
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# used in reflection to check if compose is available at runtime
-keepnames class androidx.compose.ui.platform.AndroidComposeView

##---------------End: proguard configuration for okhttp3  ----------