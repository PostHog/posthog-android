// adapted from https://github.com/getsentry/sentry-android-gradle-plugin/blob/0ce926822756c8379e281bed8c33237a400c9582/plugin-build/src/main/kotlin/io/sentry/android/gradle/tasks/SentryUploadProguardMappingsTask.kt#L16

package com.posthog.android

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Uploads should not be cached")
abstract class PostHogUploadProguardMappingsTask : PostHogCliExecTask() {
    init {
        description = "Uploads the proguard mappings file to PostHog"

        // Allows gradle to consider this task up-to-date if the inputs haven't changed
        // As this task does not have any outputs, it will always be considered to be out-of-date
        // otherwise
        // More info here
        // https://docs.gradle.org/current/userguide/more_about_tasks.html#sec:task_outcomes
        // and
        // https://docs.gradle.org/current/userguide/incremental_build.html#sec:custom_up_to_date_logic
        outputs.upToDateWhen { true }
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapIdFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract var mappingsFiles: Provider<FileCollection>

    override fun exec() {
        if (!mappingsFiles.isPresent || mappingsFiles.get().isEmpty) {
            error("[PostHog] Mapping files are missing!")
        }
        super.exec()
    }

    override fun getArguments(args: MutableList<String>) {
        val uuid = readMapIdFromFile(mapIdFile.get().asFile)
        val firstExistingFile = mappingsFiles.get().files.firstOrNull { it.exists() }

        val mappingFile =
            if (firstExistingFile == null) {
                logger.warn(
                    "None of the provided mappingFiles was found on disk. " +
                        "Upload is most likely going to be skipped",
                )
                mappingsFiles.get().files.first()
            } else {
                firstExistingFile
            }

        args.add("exp")
        args.add("proguard")
        args.add("upload")
        args.add("--path")
        args.add(mappingFile.toString())
        args.add("--map-id")
        args.add(uuid)
    }

    companion object {
        fun register(
            project: Project,
            generateMapIdTask: Provider<PostHogGenerateMapIdTask>,
            mappingFiles: Provider<FileCollection>,
            taskSuffix: String = "",
        ): TaskProvider<PostHogUploadProguardMappingsTask> {
            val uploadPostHogProguardMappingsTask =
                project.tasks.register(
                    "uploadPostHogProguardMappings$taskSuffix",
                    PostHogUploadProguardMappingsTask::class.java,
                ) {
                    dependsOn(generateMapIdTask)
                    workingDir(project.rootDir)
                    this.mapIdFile.set(generateMapIdTask.flatMap { it.outputFile })
                    this.mappingsFiles = mappingFiles
                }
            return uploadPostHogProguardMappingsTask
        }
    }
}
