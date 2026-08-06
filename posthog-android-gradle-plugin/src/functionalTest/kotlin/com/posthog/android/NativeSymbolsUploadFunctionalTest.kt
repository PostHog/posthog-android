package com.posthog.android

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the plugin against a real Android app project per supported AGP
 * version. The merged native libs directory and its `merge<Variant>NativeLibs`
 * producer are AGP internals, so these tests pin that the explicit upload
 * task depends on the producer and receives the merged `.so` files, across
 * the oldest supported AGP and a current one.
 *
 * The plugin compiles against AGP as `compileOnly`, so it must share a
 * classloader with AGP in the test build: both go on the root project's
 * buildscript classpath instead of TestKit's `withPluginClasspath`.
 */
@RunWith(Parameterized::class)
internal class NativeSymbolsUploadFunctionalTest(private val agpVersion: String) {
    @get:Rule
    val projectDir = TemporaryFolder()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "AGP {0}")
        fun agpVersions(): Collection<String> = listOf("8.0.2", "8.9.1")

        private val pluginClasspath: String by lazy {
            val metadata =
                NativeSymbolsUploadFunctionalTest::class.java.classLoader
                    .getResource("plugin-under-test-metadata.properties")
                    ?: error("plugin-under-test-metadata.properties not on the test classpath")
            val properties = metadata.openStream().use { Properties().apply { load(it) } }
            properties
                .getProperty("implementation-classpath")
                .split(File.pathSeparator)
                .joinToString(", ") { "'${it.replace("\\", "\\\\")}'" }
        }
    }

    private fun setUpProject(
        uploadNativeSymbols: Boolean,
        includeNativeSymbolSources: Boolean = false,
    ): File {
        val fakeCliLog = File(projectDir.root, "fake-cli-args.txt")
        val fakeCli = File(projectDir.root, "fake-posthog-cli")
        fakeCli.writeText("#!/bin/sh\necho \"$@\" >> ${fakeCliLog.absolutePath}\n")
        fakeCli.setExecutable(true)

        File(projectDir.root, "settings.gradle").writeText(
            """
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            include ':app'
            """.trimIndent(),
        )
        File(projectDir.root, "build.gradle").writeText(
            """
            buildscript {
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.android.tools.build:gradle:$agpVersion'
                    classpath files($pluginClasspath)
                }
            }
            """.trimIndent(),
        )
        File(projectDir.root, "gradle.properties").writeText("android.useAndroidX=true\n")
        System.getenv("ANDROID_HOME")?.let {
            File(projectDir.root, "local.properties").writeText("sdk.dir=$it\n")
        }

        val app = File(projectDir.root, "app").apply { mkdirs() }
        File(app, "src/main").mkdirs()
        File(app, "src/main/AndroidManifest.xml").writeText("<manifest />")
        File(app, "src/main/jniLibs/arm64-v8a").mkdirs()
        File(app, "src/main/jniLibs/arm64-v8a/libfake.so").writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))

        File(app, "build.gradle").writeText(
            """
            apply plugin: 'com.android.application'
            apply plugin: 'com.posthog.android'

            android {
                namespace 'com.posthog.test'
                compileSdk 34
                defaultConfig {
                    minSdk 23
                    versionCode 1
                    versionName '1.0'
                }
            }

            posthog {
                uploadNativeSymbols = $uploadNativeSymbols
                includeNativeSymbolSources = $includeNativeSymbolSources
            }

            tasks.withType(com.posthog.android.PostHogCliExecTask).configureEach {
                postHogExecutable = '${fakeCli.absolutePath}'
            }
            """.trimIndent(),
        )
        return fakeCliLog
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments(*arguments)

    @Test
    fun `explicit invocation depends on the merge task and uploads the merged libs`() {
        val fakeCliLog = setUpProject(uploadNativeSymbols = false, includeNativeSymbolSources = true)

        val result = runner(":app:uploadPostHogNativeSymbolsDebug", "--stacktrace").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:mergeDebugNativeLibs")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:uploadPostHogNativeSymbolsDebug")?.outcome)

        val cliArgs = fakeCliLog.readText()
        assertTrue(cliArgs.contains("symbol-sets upload --directory"), "got: $cliArgs")
        assertTrue(cliArgs.contains("--include-source"), "got: $cliArgs")
        val directory = cliArgs.substringAfter("--directory ").trim().split(" ").first()
        val mergedLibs = File(directory).walkTopDown().filter { it.extension == "so" }.toList()
        assertTrue(mergedLibs.any { it.name == "libfake.so" }, "merged dir $directory had: $mergedLibs")
    }

    @Test
    fun `assemble triggers the upload only when opted in`() {
        setUpProject(uploadNativeSymbols = true)
        val scheduled = runner(":app:assembleRelease", "--dry-run").build()
        assertTrue(scheduled.output.contains(":app:uploadPostHogNativeSymbolsRelease"), scheduled.output)
    }

    @Test
    fun `assemble does not trigger the upload without the opt-in`() {
        setUpProject(uploadNativeSymbols = false)
        val scheduled = runner(":app:assembleRelease", "--dry-run").build()
        assertFalse(scheduled.output.contains(":app:uploadPostHogNativeSymbolsRelease"), scheduled.output)
    }

    @Test
    fun `a failed build does not upload symbols`() {
        val fakeCliLog = setUpProject(uploadNativeSymbols = true)
        File(projectDir.root, "app/build.gradle").appendText(
            """

            def boom = tasks.register('boom') {
                dependsOn tasks.named('mergeReleaseNativeLibs')
                doLast { throw new GradleException('post-merge failure') }
            }
            tasks.named('assembleRelease') { dependsOn boom }
            """.trimIndent(),
        )

        val result = runner(":app:assembleRelease").buildAndFail()

        val upload = result.task(":app:uploadPostHogNativeSymbolsRelease")
        assertTrue(
            upload == null || upload.outcome == TaskOutcome.SKIPPED,
            "expected no upload after a failed build, got: ${'$'}{upload?.outcome}",
        )
        assertFalse(fakeCliLog.exists(), "the CLI must not run for a failed build")
    }

    @Test
    fun `debuggable variants are not hooked even when opted in`() {
        setUpProject(uploadNativeSymbols = true)
        val scheduled = runner(":app:assembleDebug", "--dry-run").build()
        assertFalse(scheduled.output.contains(":app:uploadPostHogNativeSymbolsDebug"), scheduled.output)
    }
}
