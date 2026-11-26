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

    // Every Java and Kotlin file the CLI received, as a "FILE <path>" line followed by its text.
    private lateinit var cliFiles: File

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
        cliFiles = File(root, "cli-files.txt")
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
            |  if [ "${'$'}prev" = '--source' ]; then
            |    find "${'$'}arg" -type f | sort | while read -r file; do
            |      echo "FILE ${'$'}file" >> '${cliFiles.absolutePath}'
            |      cat "${'$'}file" >> '${cliFiles.absolutePath}'
            |    done
            |  fi
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

            repositories {
                mavenCentral()
            }

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
                "--source ${File(root, "build/generated/sources/annotationProcessor/java/main")} " +
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
    fun `sources that an annotation processor generates are uploaded on a clean build`() {
        setUpProject()
        File(root, "settings.gradle").appendText("include 'processor'\n")
        File(root, "build.gradle").appendText("\ndependencies { annotationProcessor project(':processor') }\n")
        File(root, "processor/build.gradle").apply { parentFile.mkdirs() }.writeText("plugins { id 'java' }\n")
        File(root, "processor/src/main/resources/META-INF/services").mkdirs()
        File(root, "processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor")
            .writeText("com.acme.processor.Generator\n")
        File(root, "processor/src/main/java/com/acme/processor").mkdirs()
        File(root, "processor/src/main/java/com/acme/processor/Generator.java").writeText(
            """
            package com.acme.processor;

            import java.io.IOException;
            import java.io.Writer;
            import java.util.Set;
            import javax.annotation.processing.AbstractProcessor;
            import javax.annotation.processing.RoundEnvironment;
            import javax.annotation.processing.SupportedAnnotationTypes;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;

            @SupportedAnnotationTypes("*")
            public class Generator extends AbstractProcessor {
                private boolean generated;

                @Override
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (generated) return false;
                    generated = true;
                    try (Writer writer = processingEnv.getFiler().createSourceFile("com.acme.AppMapper").openWriter()) {
                        writer.write("package com.acme;\n\npublic class AppMapper {}\n");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return false;
                }

                @Override
                public SourceVersion getSupportedSourceVersion() {
                    return SourceVersion.latestSupported();
                }
            }
            """.trimIndent(),
        )

        runner("uploadPostHogJvmSources").build()

        val generated = File(root, "build/generated/sources/annotationProcessor/java/main/com/acme/AppMapper.java")
        assertTrue(cliFiles.readText().contains("FILE $generated\npackage com.acme;"), cliFiles.readText())
    }

    @Test
    fun `Java sources in another encoding reach the CLI as UTF-8`() {
        setUpProject()
        File(root, "build.gradle").appendText("\ntasks.withType(JavaCompile).configureEach { options.encoding = 'ISO-8859-1' }\n")
        File(root, "src/main/java/com/acme/App.java").writeBytes(
            "package com.acme;\n\n// Précis: été\npublic class App {}\n".toByteArray(Charsets.ISO_8859_1),
        )

        runner("jar").build()

        val copy = File(root, "build/tmp/uploadPostHogJvmSources/utf8-sources/0/com/acme/App.java")
        assertTrue(cliLog.readText().contains("--source ${copy.parentFile.parentFile.parentFile} "), cliLog.readText())
        assertTrue(cliFiles.readText(Charsets.UTF_8).contains("FILE $copy\npackage com.acme;\n\n// Précis: été\n"), cliFiles.readText())
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
