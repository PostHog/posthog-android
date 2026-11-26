package com.posthog.jvm

import com.posthog.android.POSTHOG_CLI_DEFAULT_EXECUTABLE
import com.posthog.android.resolvePostHogDotenvFile
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

/**
 * `com.posthog.jvm`: uploads the sources of a server JVM application, so PostHog error tracking
 * shows source code on the frames of the exceptions the PostHog server SDK captures. Apply it in
 * the module that builds the application, not in the libraries it uses.
 *
 * It must not touch Android Gradle plugin classes: the plugin jar only compiles against them, and
 * a server build has none on its classpath.
 */
internal abstract class PostHogJvmGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val providers = project.providers
        val extension = project.extensions.create("posthog", PostHogJvmPluginExtension::class.java)
        extension.skip.convention(booleanGradleProperty(providers, "posthog.skip"))
        extension.requireUpload.convention(booleanGradleProperty(providers, "posthog.requireUpload"))
        extension.releaseName.convention(project.name)
        extension.releaseVersion.convention(providers.gradleProperty("posthog.releaseVersion"))
        extension.cliExecutable.convention(providers.gradleProperty("posthog.cliExecutable"))

        project.pluginManager.withPlugin("com.android.application") {
            project.logger.warn(
                "[PostHog] 'com.posthog.jvm' is for server JVM applications. " +
                    "Apply 'com.posthog.android' in an Android app instead.",
            )
        }

        project.pluginManager.withPlugin("java") {
            val main =
                project.extensions.getByType(SourceSetContainer::class.java)
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            val projectDir = project.projectDir
            val compileJava = project.tasks.named(main.compileJavaTaskName, JavaCompile::class.java)
            val upload =
                project.tasks.register(UPLOAD_TASK_NAME, PostHogUploadJvmSourcesTask::class.java) {
                    // allJava also holds the Kotlin, Groovy and Scala directories of the source
                    // set. Resources stay out: they hold this task's own output. compileJava
                    // writes the sources that annotation processors generate, such as MapStruct
                    // mappers, into a directory of its own, so the upload runs after it.
                    sourceDirectories.from(
                        main.allJava.sourceDirectories,
                        compileJava.flatMap { it.options.generatedSourceOutputDirectory },
                        extension.additionalSourceDirs,
                    )
                    // The property is internal, so Gradle does not infer the tasks that generate
                    // any of the directories.
                    dependsOn(sourceDirectories, compileJava)
                    sourceEncoding.set(compileJava.map { it.options.encoding.orEmpty() })
                    buildDirectory.set(project.layout.buildDirectory)
                    outputDirectory.set(project.layout.buildDirectory.dir("generated/posthog/main"))
                    releaseName.set(extension.releaseName)
                    releaseVersion.set(extension.releaseVersion)
                    requireUpload.set(extension.requireUpload)
                    skip.set(extension.skip)
                    postHogExecutable.set(
                        extension.cliExecutable
                            .map { resolveAgainst(projectDir, it) }
                            .orElse(POSTHOG_CLI_DEFAULT_EXECUTABLE),
                    )
                    resolvePostHogDotenvFile(project)?.let { postHogDotenvFile.set(it) }
                }
            // Every archive and image of the application packages the main resources: jar,
            // bootJar, shadowJar, war, Jib and the application distribution.
            main.resources.srcDir(upload.flatMap { it.outputDirectory })
        }

        project.afterEvaluate {
            if (!project.pluginManager.hasPlugin("java")) {
                project.logger.warn(
                    "[PostHog] 'com.posthog.jvm' uploads nothing in ${project.path}, because the " +
                        "project applies no Java or Kotlin JVM plugin.",
                )
            }
        }
    }

    private fun booleanGradleProperty(
        providers: ProviderFactory,
        name: String,
    ): Provider<Boolean> = providers.gradleProperty(name).map { it.trim().toBoolean() }.orElse(false)

    // A path is relative to the project, and a bare name is looked up on PATH.
    private fun resolveAgainst(
        projectDir: File,
        executable: String,
    ): String {
        val trimmed = executable.trim()
        if (!trimmed.contains('/') && !trimmed.contains('\\')) {
            return trimmed
        }
        val file = File(trimmed)
        return if (file.isAbsolute) file.path else File(projectDir, trimmed).normalize().path
    }

    internal companion object {
        const val UPLOAD_TASK_NAME: String = "uploadPostHogJvmSources"
    }
}
