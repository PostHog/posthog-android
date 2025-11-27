package com.posthog.android

import org.gradle.api.Project

internal const val ROOT_DIR = "intermediates/posthog"

class OutputPaths(private val project: Project, variantName: String) {
    private fun dir(path: String) = project.layout.buildDirectory.dir(path)

    private val variantDirectory = "$ROOT_DIR/$variantName"

    val proguardMapIdDir = dir("$variantDirectory/proguard-mapid")
}
