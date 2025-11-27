package com.posthog.android

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.util.UUID

@DisableCachingByDefault
abstract class PostHogGenerateMapIdTask : PropertiesFileOutputTask() {
    init {
        description =
            "Generates a unique build ID to be used when uploading the PostHog mapping file"
    }

    @get:Internal
    override val outputFile: Provider<RegularFile>
        get() = output.file(POSTHOG_MAP_ID_OUTPUT)

    @get:Internal abstract val proguardMappingFiles: ConfigurableFileCollection

    @TaskAction
    fun generateProperties() {
        val outputDir = output.get().asFile
        outputDir.mkdirs()

        val proguardMappingFileHash =
            proguardMappingFiles.files.joinToString { if (it.isFile) it.contentHash() else STATIC_HASH }
        val uuid = UUID.nameUUIDFromBytes(proguardMappingFileHash.toByteArray())
        outputFile.get().asFile.writer().use { writer ->
            writer.appendLine("$POSTHOG_PROGUARD_MAPPING_MAP_ID_PROPERTY=$uuid")
        }

        logger.info("PostHogGenerateMapIdTask - outputFile: $outputFile, uuid: $uuid")
    }

    companion object {
        internal const val STATIC_HASH = "<hash>"
        internal const val POSTHOG_MAP_ID_OUTPUT = "posthog-proguard-map-id.properties"
        const val POSTHOG_PROGUARD_MAPPING_MAP_ID_PROPERTY = "io.posthog.proguard.mapid"

        fun register(
            project: Project,
            output: Provider<Directory>? = null,
            proguardMappingFile: Provider<FileCollection>?,
            taskSuffix: String = "",
        ): TaskProvider<PostHogGenerateMapIdTask> {
            val generateMapIdTask =
                project.tasks.register(
                    "postHogGenerateMapIdTask$taskSuffix",
                    PostHogGenerateMapIdTask::class.java,
                ) {
                    output?.let {
                        this.output.set(it)
                    }
                    proguardMappingFile?.let {
                        this.proguardMappingFiles.from(proguardMappingFile)
                    }
                    this.outputs.upToDateWhen { false }
                }

            return generateMapIdTask
        }
    }
}
