package com.posthog.jvm

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `com.posthog.jvm` in a plain JVM build, the oldest supported Gradle and the current one,
 * with a shell script standing in for posthog-cli.
 *
 * The build has no Android Gradle plugin on its classpath, which the plugin jar only compiles
 * against, so every test also proves that the plugin loads without it.
 */
@RunWith(Parameterized::class)
internal class JvmSourcesUploadFunctionalTest(
    private val gradleVersion: String?,
) {
    @get:Rule
    val projectDir = TemporaryFolder()

    private lateinit var cliLog: File

    // Gradle resolves the project directory to its canonical path, which differs from the
    // temporary folder's path on macOS (/private/var instead of /var).
    private val root: File get() = projectDir.root.canonicalFile

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "Gradle {0}")
        fun gradleVersions(): Collection<Array<String?>> = listOf(arrayOf("8.12"), arrayOf(null))

        private val pluginClasspath: String by lazy {
            val metadata =
                JvmSourcesUploadFunctionalTest::class.java.classLoader
                    .getResource("plugin-under-test-metadata.properties")
                    ?: error("plugin-under-test-metadata.properties not on the test classpath")
            val properties = metadata.openStream().use { Properties().apply { load(it) } }
            properties
                .getProperty("implementation-classpath")
                .split(File.pathSeparator)
                .joinToString(", ") { "'${it.replace("\\", "\\\\")}'" }
        }
    }

    @Before
    fun setUp() {
        // The fake CLI is a shell script.
        Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        cliLog = File(root, "cli-args.txt")
    }

    /**
     * Writes the build. [cliBehavior] is "upload" (writes posthog-meta.properties into the
     * --meta-out directory, as an upload does), "nothing" (exits 0 and writes nothing, as a run
     * without credentials does) or "old" (rejects the jvm subcommand, as a posthog-cli from before
     * jvm upload does). Except for "old", the CLI answers the plugin's help probe without logging.
     */
    private fun setUpProject(
        plugins: String = "id 'java'",
        posthogBlock: String = "",
        cliBehavior: String = "upload",
    ) {
        val cli = File(root, "fake-posthog-cli")
        cli.writeText(
            """
            |#!/bin/sh
            |if [ '$cliBehavior' = 'old' ]; then
            |  echo "error: unrecognized subcommand 'jvm'" >&2
            |  exit 2
            |fi
            |case " $* " in *' --help '*) echo '  --skip-without-credentials'; exit 0;; esac
            |echo "$@" >> '${cliLog.absolutePath}'
            |meta=''
            |prev=''
            |for arg in "$@"; do
            |  if [ "${'$'}prev" = '--meta-out' ]; then meta="${'$'}arg"; fi
            |  prev="${'$'}arg"
            |done
            |if [ '$cliBehavior' = 'upload' ]; then
            |  mkdir -p "${'$'}meta"
            |  printf 'io.posthog.proguard.mapid=fake-id\n' > "${'$'}meta/posthog-meta.properties"
            |fi
            """.trimMargin() + "\n",
        )
        cli.setExecutable(true)

        File(root, "settings.gradle").writeText("rootProject.name = 'demo-app'\n")
        File(root, "build.gradle").writeText(
            """
            buildscript {
                dependencies {
                    classpath files($pluginClasspath)
                }
            }
            plugins {
                $plugins
            }
            apply plugin: 'com.posthog.jvm'

            posthog {
                cliExecutable = '${cli.absolutePath}'
                $posthogBlock
            }
            """.trimIndent(),
        )
        File(root, "src/main/java/com/acme").mkdirs()
        File(root, "src/main/java/com/acme/App.java").writeText(
            "package com.acme;\n\npublic class App {}\n",
        )
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .apply { gradleVersion?.let { withGradleVersion(it) } }
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    private fun jarEntry(name: String): String? =
        ZipFile(File(root, "build/libs/demo-app.jar")).use { jar ->
            jar.getEntry(name)?.let { entry -> jar.getInputStream(entry).bufferedReader().readText() }
        }

    @Test
    fun `the jar carries the id the CLI wrote, and the upload runs on every build`() {
        setUpProject()

        val first = runner("jar").build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":uploadPostHogJvmSources")?.outcome)
        assertEquals("io.posthog.proguard.mapid=fake-id\n", jarEntry("posthog-meta.properties"))
        assertEquals(
            "jvm upload --source ${File(root, "src/main/java")} " +
                "--meta-out ${File(root, "build/generated/posthog/main")} " +
                "--skip-without-credentials --release-name demo-app",
            cliLog.readLines().single(),
        )

        val second = runner("jar").build()

        assertTrue(second.output.contains("Reusing configuration cache"), second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":uploadPostHogJvmSources")?.outcome)
        assertEquals(2, cliLog.readLines().size)
    }

    @Test
    fun `configured values and Kotlin sources reach the CLI`() {
        setUpProject(
            plugins = "id 'org.jetbrains.kotlin.jvm' version '${System.getProperty("kotlinVersion")}'",
            posthogBlock =
                """
                releaseName = 'billing'
                releaseVersion = '1.4.0'
                additionalSourceDirs.from('shared/src/main/java')
                """.trimIndent(),
        )
        File(root, "src/main/kotlin/com/acme").mkdirs()
        File(root, "src/main/kotlin/com/acme/Billing.kt").writeText("package com.acme\n")
        File(root, "shared/src/main/java").mkdirs()

        runner("uploadPostHogJvmSources").build()

        val args = cliLog.readLines().single()
        for (dir in listOf("src/main/kotlin", "src/main/java", "shared/src/main/java")) {
            assertTrue(args.contains("--source ${File(root, dir)} "), args)
        }
        assertTrue(args.endsWith("--release-name billing --release-version 1.4.0"), args)
    }

    @Test
    fun `a run that uploads nothing ships no id, and fails only when the upload is required`() {
        setUpProject(cliBehavior = "nothing")

        val skipped = runner("jar").build()

        assertTrue(skipped.output.contains("The source upload was skipped"), skipped.output)
        assertNull(jarEntry("posthog-meta.properties"))

        val required = runner("jar", "-Pposthog.requireUpload=true").buildAndFail()

        assertEquals(TaskOutcome.FAILED, required.task(":uploadPostHogJvmSources")?.outcome)
        assertTrue(required.output.contains("did not write"), required.output)
        assertFalse(cliLog.readLines().last().contains("--skip-without-credentials"))
    }

    @Test
    fun `a missing CLI skips the upload, and fails only when the upload is required`() {
        setUpProject(posthogBlock = "cliExecutable = '/nonexistent/posthog-cli'")

        val skipped = runner("jar").build()

        assertEquals(TaskOutcome.SUCCESS, skipped.task(":uploadPostHogJvmSources")?.outcome)
        assertTrue(skipped.output.contains("posthog-cli was not found"), skipped.output)
        assertNull(jarEntry("posthog-meta.properties"))

        val required = runner("jar", "-Pposthog.requireUpload=true").buildAndFail()

        assertEquals(TaskOutcome.FAILED, required.task(":uploadPostHogJvmSources")?.outcome)
        assertTrue(required.output.contains("posthog-cli was not found"), required.output)
    }

    @Test
    fun `an old CLI skips the upload, and fails only when the upload is required`() {
        setUpProject(cliBehavior = "old")

        val skipped = runner("jar").build()

        assertEquals(TaskOutcome.SUCCESS, skipped.task(":uploadPostHogJvmSources")?.outcome)
        assertTrue(skipped.output.contains("is too old"), skipped.output)
        assertNull(jarEntry("posthog-meta.properties"))

        val required = runner("jar", "-Pposthog.requireUpload=true").buildAndFail()

        assertEquals(TaskOutcome.FAILED, required.task(":uploadPostHogJvmSources")?.outcome)
        assertTrue(required.output.contains("is too old"), required.output)
    }

    @Test
    fun `skip removes the id an earlier build packaged`() {
        setUpProject()
        runner("jar").build()
        assertTrue(jarEntry("posthog-meta.properties") != null)

        runner("jar", "-Pposthog.skip=true").build()

        assertNull(jarEntry("posthog-meta.properties"))
        assertEquals(1, cliLog.readLines().size)
    }
}
