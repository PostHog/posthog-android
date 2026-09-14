plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":posthog"))
    val ktorVersion = "2.3.7"
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:${PosthogBuildConfig.Dependencies.OKHTTP}"))
    implementation("com.squareup.okhttp3:okhttp")
}

kotlin { jvmToolchain(PosthogBuildConfig.Build.JDK_VERSION) }
java {
    sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.postHogConfig(false)
}
tasks.processResources {
    filesMatching("sdk-versions.properties") {
        expand(mapOf("serverVersion" to project.property("serverVersion"), "androidVersion" to project.property("androidVersion")))
    }
}
tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach { enabled = false }
