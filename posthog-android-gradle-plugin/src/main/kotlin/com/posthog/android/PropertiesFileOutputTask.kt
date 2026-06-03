// Portions of this file are derived from getsentry/sentry-android-gradle-plugin
// Copyright (c) 2020 Sentry
// Licensed under the MIT License: https://github.com/getsentry/sentry-android-gradle-plugin/blob/main/LICENSE

package com.posthog.android

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "abstract task, should not be used directly")
public abstract class PropertiesFileOutputTask : DirectoryOutputTask() {
    @get:Internal
    public abstract val outputFile: Provider<RegularFile>
}
