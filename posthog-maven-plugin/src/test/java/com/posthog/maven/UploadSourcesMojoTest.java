package com.posthog.maven;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UploadSourcesMojoTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File basedir;
    private File argsFile;
    private UploadSourcesMojo mojo;

    @Before
    public void setUp() throws IOException {
        basedir = temp.newFolder("project");
        argsFile = new File(temp.getRoot(), "cli-args.txt");

        MavenProject project = new MavenProject();
        project.setFile(new File(basedir, "pom.xml"));
        Build build = new Build();
        build.setDirectory(new File(basedir, "target").getAbsolutePath());
        build.setOutputDirectory(new File(basedir, "target/classes").getAbsolutePath());
        project.getModel().setBuild(build);

        mojo = new UploadSourcesMojo();
        mojo.project = project;
        mojo.releaseName = "demo-app";
        mojo.releaseVersion = "1.2.3";
        mojo.outputDirectory = new File(basedir, "target/generated-resources/posthog");
    }

    @Test
    public void uploadsExistingSourceRootsAndRegistersTheResourceDirectory() throws Exception {
        assumeUnix();
        File java = sourceRoot("src/main/java");
        File kotlin = sourceRoot("src/main/kotlin");
        mojo.project.addCompileSourceRoot(java.getAbsolutePath());
        mojo.project.addCompileSourceRoot(new File(basedir, "src/main/missing").getAbsolutePath());
        mojo.project.addCompileSourceRoot(kotlin.getAbsolutePath());
        mojo.dotenvFile = new File(basedir, ".env");
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        assertEquals(
                Arrays.asList(
                        "--dotenv-file",
                        mojo.dotenvFile.getAbsolutePath(),
                        "jvm",
                        "upload",
                        "--source",
                        java.getAbsolutePath(),
                        "--source",
                        kotlin.getAbsolutePath(),
                        "--meta-out",
                        mojo.outputDirectory.getAbsolutePath(),
                        "--skip-without-credentials",
                        "--release-name",
                        "demo-app",
                        "--release-version",
                        "1.2.3"),
                recordedArgs());

        List<Resource> resources = mojo.project.getResources();
        assertEquals(1, resources.size());
        assertEquals(mojo.outputDirectory.getAbsolutePath(), resources.get(0).getDirectory());
        assertEquals(
                Collections.singletonList("posthog-meta.properties"),
                resources.get(0).getIncludes());
    }

    @Test
    public void kotlinSourceDirsAreUploadedWithTheCompileSourceRoots() throws Exception {
        assumeUnix();
        File java = sourceRoot("src/main/java");
        File kotlin = sourceRoot("src/main/kotlin");
        mojo.project.addCompileSourceRoot(java.getAbsolutePath());
        // The mixed Java and Kotlin setup from the Kotlin docs, which lists both directories.
        Xpp3Dom sourceDirs = new Xpp3Dom("sourceDirs");
        for (String dir : Arrays.asList("src/main/kotlin", java.getAbsolutePath())) {
            Xpp3Dom sourceDir = new Xpp3Dom("sourceDir");
            sourceDir.setValue(dir);
            sourceDirs.addChild(sourceDir);
        }
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        configuration.addChild(sourceDirs);
        PluginExecution compile = new PluginExecution();
        compile.addGoal("compile");
        compile.setConfiguration(configuration);
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.jetbrains.kotlin");
        plugin.setArtifactId("kotlin-maven-plugin");
        plugin.addExecution(compile);
        mojo.project.getBuild().addPlugin(plugin);
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        assertEquals(
                Arrays.asList(
                        "--source", java.getAbsolutePath(), "--source", kotlin.getAbsolutePath()),
                recordedArgs().subList(2, 6));
        assertEquals("--meta-out", recordedArgs().get(6));
    }

    @Test
    public void configuredSourceRootsReplaceTheCompileSourceRoots() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        File custom = sourceRoot("custom");
        mojo.sourceRoots = Collections.singletonList(custom);
        mojo.releaseName = " ";
        mojo.releaseVersion = null;
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        assertEquals(
                Arrays.asList(
                        "jvm",
                        "upload",
                        "--source",
                        custom.getAbsolutePath(),
                        "--meta-out",
                        mojo.outputDirectory.getAbsolutePath(),
                        "--skip-without-credentials"),
                recordedArgs());
    }

    @Test
    public void registersTheResourceDirectoryOnce() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();
        mojo.execute();

        assertEquals(1, mojo.project.getResources().size());
    }

    @Test
    public void failsTheBuildWhenTheCliFails() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(3, false).getAbsolutePath();

        try {
            mojo.execute();
            fail("expected a MojoExecutionException");
        } catch (MojoExecutionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exited with code 3"));
        }
        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void skipsWhenTheCliUploadsNothing() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, false).getAbsolutePath();

        mojo.execute();

        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void aRequiredUploadFailsTheBuildWhenTheCliWritesNoFile() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, false).getAbsolutePath();
        mojo.requireUpload = true;

        try {
            mojo.execute();
            fail("expected a MojoExecutionException");
        } catch (MojoExecutionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not write"));
        }
        assertFalse(recordedArgs().contains("--skip-without-credentials"));
        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void skipsWhenTheCliIsMissing() throws Exception {
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = new File(temp.getRoot(), "no-such-posthog-cli").getAbsolutePath();
        File stale = staleMetaProperties(mojo.outputDirectory);

        mojo.execute();

        assertFalse(stale.exists());
        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void anOldCliSkipsTheUploadUnlessItIsRequired() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = oldCli().getAbsolutePath();

        mojo.execute();

        assertTrue(mojo.project.getResources().isEmpty());

        mojo.requireUpload = true;
        try {
            mojo.execute();
            fail("expected a MojoExecutionException");
        } catch (MojoExecutionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("too old"));
        }
    }

    @Test
    public void aRequiredUploadFailsWithAnInstallHintWhenTheCliIsMissing() throws Exception {
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = new File(temp.getRoot(), "no-such-posthog-cli").getAbsolutePath();
        mojo.requireUpload = true;

        try {
            mojo.execute();
            fail("expected a MojoExecutionException");
        } catch (MojoExecutionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("posthog.cliExecutable"));
        }
    }

    @Test
    public void skipRunsNothingAndRemovesAStaleId() throws Exception {
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();
        File staleGenerated = staleMetaProperties(mojo.outputDirectory);
        File staleClasses = staleMetaProperties(new File(basedir, "target/classes"));
        mojo.skip = true;

        mojo.execute();

        assertFalse(argsFile.exists());
        assertFalse(staleGenerated.exists());
        assertFalse(staleClasses.exists());
        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void skipsWhenNoSourceRootExists() throws Exception {
        mojo.project.addCompileSourceRoot(new File(basedir, "src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        assertFalse(argsFile.exists());
        assertTrue(mojo.project.getResources().isEmpty());
    }

    @Test
    public void resolveUsesTheConfiguredExecutableVerbatim() {
        assertEquals(
                "/custom/posthog-cli",
                PostHogCli.resolve(" /custom/posthog-cli ", env(""), temp.getRoot(), false));
    }

    @Test
    public void resolvePrefersTheDotPosthogInstallOverPath() throws Exception {
        assumeUnix();
        File home = temp.newFolder("home");
        File installed = executable(new File(home, ".posthog/posthog-cli"));
        File pathDir = temp.newFolder("bin");
        executable(new File(pathDir, "posthog-cli"));

        assertEquals(
                installed.getAbsolutePath(),
                PostHogCli.resolve(null, env(pathDir.getAbsolutePath()), home, false));
    }

    @Test
    public void resolveFallsBackToPathThenNothing() throws Exception {
        assumeUnix();
        File home = temp.newFolder("home");
        File pathDir = temp.newFolder("bin");
        File onPath = executable(new File(pathDir, "posthog-cli"));

        assertEquals(
                onPath.getAbsolutePath(),
                PostHogCli.resolve("", env(pathDir.getAbsolutePath()), home, false));
        // A temp home and an empty PATH only leave the machine-wide install locations, so no
        // CLI is expected unless this machine has one installed in one of them.
        String resolved = PostHogCli.resolve(null, env(""), home, false);
        assertTrue(resolved, resolved == null || resolved.startsWith("/"));
    }

    @Test
    public void resolveOnWindowsPrefersTheExeThenTheNpmShim() throws Exception {
        File home = temp.newFolder("home");
        File exeDir = temp.newFolder("exe");
        File exe = executable(new File(exeDir, "posthog-cli.exe"));
        File shimDir = temp.newFolder("shim");
        executable(new File(shimDir, "posthog-cli.cmd"));
        String both = exeDir.getAbsolutePath() + File.pathSeparator + shimDir.getAbsolutePath();

        assertEquals(exe.getAbsolutePath(), PostHogCli.resolve(null, env(both), home, true));
        assertEquals(
                "posthog-cli",
                PostHogCli.resolve(null, env(shimDir.getAbsolutePath()), home, true));
        assertEquals(null, PostHogCli.resolve(null, env(""), home, true));
    }

    @Test
    public void commandLineWrapsABareNameInCmdOnWindows() {
        List<String> args = Arrays.asList("jvm", "upload");

        assertEquals(
                Arrays.asList("cmd", "/c", "posthog-cli", "jvm", "upload"),
                PostHogCli.commandLine("posthog-cli", args, true));
        assertEquals(
                Arrays.asList("C:\\bin\\posthog-cli.exe", "jvm", "upload"),
                PostHogCli.commandLine("C:\\bin\\posthog-cli.exe", args, true));
        assertEquals(
                Arrays.asList("/bin/posthog-cli", "jvm", "upload"),
                PostHogCli.commandLine("/bin/posthog-cli", args, false));
    }

    private File sourceRoot(String relative) {
        File dir = new File(basedir, relative);
        assertTrue(dir.mkdirs());
        return dir;
    }

    private File staleMetaProperties(File dir) throws IOException {
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File file = new File(dir, "posthog-meta.properties");
        Files.write(file.toPath(), "io.posthog.proguard.mapid=stale\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * A shell script standing in for posthog-cli. It answers the plugin's help probe like a CLI
     * with jvm upload. Otherwise it records its arguments one per line, writes
     * posthog-meta.properties into the --meta-out directory when asked to, and exits with the
     * given code.
     */
    private File fakeCli(int exitCode, boolean writeMetaProperties) throws IOException {
        String script =
                "#!/bin/sh\n"
                        + "case \" $* \" in *' --help '*) echo '  --skip-without-credentials'; exit 0;; esac\n"
                        + "printf '%s\\n' \"$@\" > '"
                        + argsFile.getAbsolutePath()
                        + "'\n"
                        + "meta=''\n"
                        + "prev=''\n"
                        + "for arg in \"$@\"; do\n"
                        + "  if [ \"$prev\" = '--meta-out' ]; then meta=\"$arg\"; fi\n"
                        + "  prev=\"$arg\"\n"
                        + "done\n"
                        + (writeMetaProperties
                                ? "mkdir -p \"$meta\"\n"
                                        + "printf 'io.posthog.proguard.mapid=fake-id\\n' > \"$meta/posthog-meta.properties\"\n"
                                : "")
                        + "echo fake-id\n"
                        + "exit "
                        + exitCode
                        + "\n";
        File cli = new File(temp.getRoot(), "fake-posthog-cli-" + exitCode + "-" + writeMetaProperties);
        Files.write(cli.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(cli.setExecutable(true));
        return cli;
    }

    /** A posthog-cli from before jvm upload, which rejects the subcommand like clap does. */
    private File oldCli() throws IOException {
        String script =
                "#!/bin/sh\n"
                        + "echo \"error: unrecognized subcommand 'jvm'\" >&2\n"
                        + "exit 2\n";
        File cli = new File(temp.getRoot(), "old-posthog-cli");
        Files.write(cli.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(cli.setExecutable(true));
        return cli;
    }

    private List<String> recordedArgs() throws IOException {
        return Files.readAllLines(argsFile.toPath(), StandardCharsets.UTF_8);
    }

    private static File executable(File file) throws IOException {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
        return file;
    }

    private static Map<String, String> env(String path) {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", path);
        return env;
    }

    private static void assumeUnix() {
        Assume.assumeFalse(PostHogCli.isWindows());
    }
}
