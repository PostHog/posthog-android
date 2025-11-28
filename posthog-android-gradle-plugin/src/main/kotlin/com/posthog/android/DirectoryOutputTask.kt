// borrowed from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/tasks/DirectoryOutputTask.kt#L3

package com.posthog.android

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "abstract task, should not be used directly")
abstract class DirectoryOutputTask : DefaultTask() {
    @get:OutputDirectory abstract val output: DirectoryProperty
}
