// copied from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/tasks/PropertiesFileOutputTask.kt#L6

package com.posthog.android

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "abstract task, should not be used directly")
abstract class PropertiesFileOutputTask : DirectoryOutputTask() {
    @get:Internal abstract val outputFile: Provider<RegularFile>
}
