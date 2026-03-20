plugins {
    id("java-library")
    id("application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.posthog.java.sample.PostHogJavaExample")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":posthog-server"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")

    // Example logging
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
