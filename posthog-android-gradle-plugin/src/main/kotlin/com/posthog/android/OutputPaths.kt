// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/sourcecontext/OutputPaths.kt#L1-L2

package com.posthog.android

import org.gradle.api.Project

internal const val ROOT_DIR = "intermediates/posthog"

internal class OutputPaths(private val project: Project, variantName: String) {
    private fun dir(path: String) = project.layout.buildDirectory.dir(path)

    private val variantDirectory = "$ROOT_DIR/$variantName"

    val proguardMapIdDir = dir("$variantDirectory/proguard-mapid")
}
