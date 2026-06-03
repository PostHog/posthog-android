// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import org.gradle.api.Project

internal const val ROOT_DIR = "intermediates/posthog"

internal class OutputPaths(private val project: Project, variantName: String) {
    private fun dir(path: String) = project.layout.buildDirectory.dir(path)

    private val variantDirectory = "$ROOT_DIR/$variantName"

    val proguardMapIdDir = dir("$variantDirectory/proguard-mapid")
}
