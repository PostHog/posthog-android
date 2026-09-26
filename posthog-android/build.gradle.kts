@file:Suppress("ktlint:standard:max-line-length")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile


version = properties["androidVersion"].toString()

plugins {
    id("com.android.library")
    kotlin("android")

    // publish
    `maven-publish`
    signing
    id("org.jetbrains.dokka")

    // tests
    id("org.jetbrains.kotlinx.kover")

    // compatibility
    id("ru.vyarus.animalsniffer")
}

android {
    namespace = "com.posthog.android"
    compileSdk = PosthogBuildConfig.Android.COMPILE_SDK

    defaultConfig {
        minSdk = PosthogBuildConfig.Android.MIN_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildFeatures {
            buildConfig = true
        }

        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
        targetCompatibility = PosthogBuildConfig.Build.JAVA_VERSION
    }

    testOptions {
        animationsDisabled = true
        unitTests.apply {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "1g" }
        }
    }

    lint {
        warningsAsErrors = true
        checkDependencies = true
        abortOnError = true
        ignoreTestSources = true

        // lint runs only for debug build
        checkReleaseBuilds = false

        baseline = File("lint-baseline.xml")
        disable.add("GradleDependency")
    }

    androidComponents.beforeVariants {
        it.enable = !PosthogBuildConfig.shouldSkipDebugVariant(it.name)
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(PosthogBuildConfig.Build.JDK_VERSION)
    compilerOptions.postHogConfig()
}

val composeTestCompiler by configurations.creating

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.postHogConfig(false)
    if (name.endsWith("UnitTestKotlin")) {
        pluginClasspath.from(composeTestCompiler)
    }
}

animalsniffer {
    // Android lint handles API compatibility checks; the previous plugin did not register Android targets.
    defaultTargets = emptySet()
}

configurations.configureEach {
    // empty artifact since Kotlin 1.9 (merged into kotlin-stdlib); its alignment
    // constraint resolves unstably under dependency locking
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

dependencies {
    // runtime
    api(project(mapOf("path" to ":posthog")))
    implementation(kotlin("stdlib-jdk8", PosthogBuildConfig.Kotlin.KOTLIN))
    implementation("androidx.lifecycle:lifecycle-process:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    implementation("androidx.lifecycle:lifecycle-common-java8:${PosthogBuildConfig.Dependencies.LIFECYCLE}")
    implementation("androidx.core:core:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    implementation("com.squareup.curtains:curtains:${PosthogBuildConfig.Dependencies.CURTAINS}")

    // compile only
    compileOnly("androidx.compose.ui:ui:${PosthogBuildConfig.Dependencies.ANDROIDX_COMPOSE}")
    compileOnly("com.google.firebase:firebase-messaging:${PosthogBuildConfig.Dependencies.FIREBASE_MESSAGING}")

    // compatibility
    signature("org.codehaus.mojo.signature:java18:${PosthogBuildConfig.Plugins.SIGNATURE_JAVA18}@signature")
    signature(
        "net.sf.androidscents.signature:android-api-level-${PosthogBuildConfig.Android.MIN_SDK}:${PosthogBuildConfig.Plugins.ANIMAL_SNIFFER_SDK_VERSION}@signature",
    )
    signature(
        "com.toasttab.android:gummy-bears-api-${PosthogBuildConfig.Android.MIN_SDK}:${PosthogBuildConfig.Plugins.GUMMY_BEARS_API}@signature",
    )

    // tests
    composeTestCompiler("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation(testFixtures(project(":posthog")))
    // exercises the Firebase-present token fetch path via mockStatic
    testImplementation("com.google.firebase:firebase-messaging:${PosthogBuildConfig.Dependencies.FIREBASE_MESSAGING}")
    testImplementation("org.mockito.kotlin:mockito-kotlin:${PosthogBuildConfig.Dependencies.MOCKITO}")
    testImplementation("org.mockito:mockito-inline:${PosthogBuildConfig.Dependencies.MOCKITO_INLINE}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${PosthogBuildConfig.Dependencies.OKHTTP}")
    testImplementation("com.google.code.gson:gson:${PosthogBuildConfig.Dependencies.GSON}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${PosthogBuildConfig.Kotlin.KOTLIN}")
    testImplementation("androidx.test:runner:${PosthogBuildConfig.Dependencies.ANDROIDX_RUNNER}")
    testImplementation("androidx.test.ext:junit:${PosthogBuildConfig.Dependencies.ANDROIDX_JUNIT}")
    testImplementation("androidx.test:core:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:core-ktx:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("androidx.test:rules:${PosthogBuildConfig.Dependencies.ANDROIDX_CORE}")
    testImplementation("org.robolectric:robolectric:${PosthogBuildConfig.Dependencies.ROBOLECTRIC}")
    testImplementation("androidx.activity:activity-compose:1.13.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.material3:material3")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui:${PosthogBuildConfig.Dependencies.ANDROIDX_COMPOSE}") {
        exclude(group = "androidx.savedstate", module = "savedstate")
    }
}

// Compile against Compose as usual, but prove the optional runtime is genuinely absent.
// Inspect jar contents rather than AGP's transformed filenames (which may be classes.jar).
val testWithoutCompose by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs native interaction resolution with Compose removed from the runtime classpath."
    val source = tasks.named<Test>("testReleaseUnitTest").get()
    testClassesDirs = source.testClassesDirs
    classpath =
        source.classpath.filter { file ->
            !file.isFile || file.extension != "jar" ||
                ZipFile(file).use { jar ->
                    jar.entries().asSequence().none { entry ->
                        // Keep AGP's merged R jar: it also contains our own SDK resource IDs.
                        entry.name.startsWith("androidx/compose/") && entry.name.endsWith(".class") &&
                            !entry.name.endsWith("/R.class") && !entry.name.substringAfterLast('/').startsWith("R$")
                    }
                }
        }
    javaLauncher.set(source.javaLauncher)
    systemProperties(source.systemProperties)
    systemProperty("posthog.test.noCompose", "true")
    maxHeapSize = "1g"
    useJUnit()
    filter.includeTestsMatching("com.posthog.android.internal.InteractionTargetTest")
}

tasks.named("check") {
    dependsOn(testWithoutCompose)
}

project.publishingAndroidConfig()
project.javadocConfig()
