package com.posthog.maven;

import static org.junit.Assert.assertArrayEquals;
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
    private File classes;
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
        classes = new File(basedir, "target/classes");
    }

    @Test
    public void uploadsExistingSourceRootsAndWritesTheIdIntoTheClasses() throws Exception {
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
                        classes.getAbsolutePath(),
                        "--skip-without-credentials",
                        "--release-name",
                        "demo-app",
                        "--release-version",
                        "1.2.3"),
                recordedArgs());
        assertTrue(metaProperties().isFile());
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
                        classes.getAbsolutePath(),
                        "--skip-without-credentials"),
                recordedArgs());
    }

    @Test
    public void javaSourcesInAnotherEncodingAreUploadedAsUtf8() throws Exception {
        assumeUnix();
        File java = sourceRoot("src/main/java");
        File javaFile = new File(java, "com/acme/Price.java");
        assertTrue(javaFile.getParentFile().mkdirs());
        // 0x80 is the euro sign in windows-1252 and a control character in ISO-8859-1.
        byte[] cp1252 = "package com.acme;\n// 5 \u20ac\nclass Price {}\n".getBytes("windows-1252");
        Files.write(javaFile.toPath(), cp1252);
        File kotlinFile = new File(java, "com/acme/Tax.kt");
        byte[] kotlin = "package com.acme\n// 5 \u20ac\n".getBytes(StandardCharsets.UTF_8);
        Files.write(kotlinFile.toPath(), kotlin);
        mojo.project.addCompileSourceRoot(java.getAbsolutePath());
        // The compiler's own encoding wins over the project property.
        mojo.project.getProperties().setProperty("project.build.sourceEncoding", "ISO-8859-1");
        Xpp3Dom encoding = new Xpp3Dom("encoding");
        encoding.setValue("windows-1252");
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        configuration.addChild(encoding);
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        compiler.setConfiguration(configuration);
        mojo.project.getBuild().addPlugin(compiler);
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        File copy = new File(basedir, "target/posthog/utf8-sources/0");
        assertEquals(Arrays.asList("--source", copy.getAbsolutePath()), recordedArgs().subList(2, 4));
        assertEquals(
                "package com.acme;\n// 5 \u20ac\nclass Price {}\n",
                new String(
                        Files.readAllBytes(new File(copy, "com/acme/Price.java").toPath()),
                        StandardCharsets.UTF_8));
        assertArrayEquals(kotlin, Files.readAllBytes(new File(copy, "com/acme/Tax.kt").toPath()));
        assertArrayEquals(cp1252, Files.readAllBytes(javaFile.toPath()));
    }

    @Test
    public void anNpmLauncherRunsWhenNeitherItNorNodeIsOnPath() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        // npm links posthog-cli to a `#!/usr/bin/env node` script next to node, in a directory
        // such as /opt/homebrew/bin that a build started from an IDE may not have on PATH.
        File npmBin = temp.newFolder("npm-bin");
        File node = new File(npmBin, "node");
        Files.write(node.toPath(), "#!/bin/sh\nexec /bin/sh \"$@\"\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(node.setExecutable(true));
        File launcher = new File(npmBin, "posthog-cli");
        String script = new String(Files.readAllBytes(fakeCli(0, true).toPath()), StandardCharsets.UTF_8);
        Files.write(
                launcher.toPath(),
                script.replace("#!/bin/sh", "#!/usr/bin/env node").getBytes(StandardCharsets.UTF_8));
        assertTrue(launcher.setExecutable(true));
        mojo.cliExecutable = launcher.getAbsolutePath();

        mojo.execute();

        assertTrue(metaProperties().isFile());
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
        assertFalse(metaProperties().exists());
    }

    @Test
    public void skipsWhenTheCliUploadsNothing() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, false).getAbsolutePath();

        mojo.execute();

        assertFalse(metaProperties().exists());
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
        assertFalse(metaProperties().exists());
    }

    @Test
    public void skipsWhenTheCliIsMissing() throws Exception {
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = new File(temp.getRoot(), "no-such-posthog-cli").getAbsolutePath();
        File stale = staleMetaProperties(classes);

        mojo.execute();

        assertFalse(stale.exists());
        assertFalse(metaProperties().exists());
    }

    @Test
    public void anOldCliSkipsTheUploadUnlessItIsRequired() throws Exception {
        assumeUnix();
        mojo.project.addCompileSourceRoot(sourceRoot("src/main/java").getAbsolutePath());
        mojo.cliExecutable = oldCli().getAbsolutePath();

        mojo.execute();

        assertFalse(metaProperties().exists());

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
        File stale = staleMetaProperties(classes);
        mojo.skip = true;

        mojo.execute();

        assertFalse(argsFile.exists());
        assertFalse(stale.exists());
    }

    @Test
    public void skipsWhenNoSourceRootExists() throws Exception {
        mojo.project.addCompileSourceRoot(new File(basedir, "src/main/java").getAbsolutePath());
        mojo.cliExecutable = fakeCli(0, true).getAbsolutePath();

        mojo.execute();

        assertFalse(argsFile.exists());
        assertFalse(metaProperties().exists());
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
        assertEquals(null, PostHogCli.resolve(null, env(shimDir.getAbsolutePath()), home, true));
        assertEquals(null, PostHogCli.resolve(null, env(""), home, true));

        // The shim starts the binary the npm package installed, so that binary runs instead,
        // without cmd: from a global install, and from a project's node_modules/.bin.
        String binary = "@posthog/cli/node_modules/.bin_real/posthog-cli.exe";
        File globalPrefix = temp.newFolder("npm-global");
        executable(new File(globalPrefix, "posthog-cli.cmd"));
        File globalExe = executable(new File(globalPrefix, "node_modules/" + binary));
        File projectBin = temp.newFolder("project-npm", "node_modules", ".bin");
        executable(new File(projectBin, "posthog-cli.cmd"));
        File projectExe = executable(new File(projectBin.getParentFile(), binary));

        assertEquals(
                globalExe.getAbsolutePath(),
                PostHogCli.resolve(null, env(globalPrefix.getAbsolutePath()), home, true));
        assertEquals(
                projectExe.getAbsolutePath(),
                PostHogCli.resolve(null, env(projectBin.getAbsolutePath()), home, true));
    }

    @Test
    public void cmdUnsafeValuesAreFound() {
        for (String unsafe :
                Arrays.asList(
                        "v1&whoami",
                        "C:\\work\\R&D\\src",
                        "a|b",
                        "a>b",
                        "%PATH%",
                        "!x!",
                        "say \"hi\"",
                        "a^b",
                        "a\nb")) {
            assertEquals(unsafe, PostHogCli.firstUnsafeForCmd(Arrays.asList("--source", unsafe)));
        }
        assertEquals(
                null,
                PostHogCli.firstUnsafeForCmd(
                        Arrays.asList(
                                "posthog-cli",
                                "--source",
                                "C:\\My Projects\\app-1\\src\\main\\java",
                                "--release-version",
                                "1.2.3+build.4",
                                "1.0(5)")));
    }

    @Test
    public void launcherPathPrependsOnlyTheDirectoriesPathLacks() throws Exception {
        assumeUnix();
        File cliDir = temp.newFolder("cli-bin");
        File nodeDir = temp.newFolder("node-bin");
        File node = executable(new File(nodeDir, "node"));
        List<File> nodeLocations = Collections.singletonList(node);
        String cli = new File(cliDir, "posthog-cli").getAbsolutePath();
        String otherDir = temp.newFolder("other-bin").getAbsolutePath();

        assertEquals(
                cliDir.getAbsolutePath()
                        + File.pathSeparator
                        + nodeDir.getAbsolutePath()
                        + File.pathSeparator
                        + otherDir,
                PostHogCli.launcherPath(cli, env(otherDir), temp.getRoot(), false, nodeLocations));
        String both = cliDir.getAbsolutePath() + File.pathSeparator + nodeDir.getAbsolutePath();
        assertEquals(
                null, PostHogCli.launcherPath(cli, env(both), temp.getRoot(), false, nodeLocations));
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

    private File metaProperties() {
        return new File(classes, "posthog-meta.properties");
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
