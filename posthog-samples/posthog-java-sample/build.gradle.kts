plugins {
    id("java-library")
    id("application")
}

java {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}

application {
    mainClass.set("com.posthog.java.sample.PostHogJavaExample")
}

dependencies {
    implementation(project(":posthog-server"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")

    // Example logging
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
