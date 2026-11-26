package com.posthog.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Uploads the project's sources to PostHog, so error tracking can show source code on the frames
 * of exceptions this build throws, and writes the id they were uploaded under into {@code
 * posthog-meta.properties}. The PostHog server SDK reads that file at startup and sends the id on
 * every exception frame.
 *
 * <p>Runs {@code posthog-cli jvm upload}. The file goes into a generated resources directory, never
 * into {@code src}, so every build carries the id of the sources it compiled.
 */
@Mojo(name = "upload-sources", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class UploadSourcesMojo extends AbstractMojo {
    static final String META_PROPERTIES_FILE = "posthog-meta.properties";

    private static final String KOTLIN_MAVEN_PLUGIN = "org.jetbrains.kotlin:kotlin-maven-plugin";

    private static final String INSTALL_HINT =
            ". Install it (npm install -g @posthog/cli) or set posthog.cliExecutable.";
    private static final String UPDATE_HINT =
            ". Update it (npm install -g @posthog/cli@latest or brew upgrade posthog-cli) or set"
                    + " posthog.cliExecutable.";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Skips the upload. The build then ships no id, so its exceptions show no source code. */
    @Parameter(property = "posthog.skip", defaultValue = "false")
    boolean skip;

    /**
     * Fails the build when posthog-cli is missing or has no jvm upload command, or PostHog
     * credentials are missing. By default the upload is skipped with a warning instead, so that
     * builds on machines without PostHog set up keep working. Set it in CI, so that a missing token
     * fails the build instead of shipping a build whose exceptions show no source code. A failed
     * upload fails the build either way.
     */
    @Parameter(property = "posthog.requireUpload", defaultValue = "false")
    boolean requireUpload;

    /**
     * Path to posthog-cli. When unset, the plugin looks in {@code ~/.posthog/posthog-cli}, then on
     * PATH, then in common install locations.
     */
    @Parameter(property = "posthog.cliExecutable")
    String cliExecutable;

    /**
     * A dotenv file posthog-cli reads its credentials from ({@code POSTHOG_CLI_HOST}, {@code
     * POSTHOG_CLI_PROJECT_ID}, {@code POSTHOG_CLI_API_KEY}). Without one, posthog-cli reads the
     * environment and the credentials saved by {@code posthog-cli login}.
     */
    @Parameter(property = "posthog.dotenvFile")
    File dotenvFile;

    /** The release the uploaded sources are associated with. */
    @Parameter(property = "posthog.releaseName", defaultValue = "${project.artifactId}")
    String releaseName;

    /**
     * The version of that release. When unset, posthog-cli uses the current git commit, from the
     * repository or from the CI environment, so every deployed commit is its own release. A
     * project version such as {@code 0.0.1-SNAPSHOT} often stays the same across deploys. A build
     * with no git commit and no version uploads its sources without a release.
     */
    @Parameter(property = "posthog.releaseVersion")
    String releaseVersion;

    /** Where posthog-cli writes {@code posthog-meta.properties}. Added as a resource directory. */
    @Parameter(
            property = "posthog.outputDirectory",
            defaultValue = "${project.build.directory}/generated-resources/posthog",
            required = true)
    File outputDirectory;

    /**
     * The source directories to upload. Defaults to the project's compile source roots and the
     * {@code <sourceDirs>} that kotlin-maven-plugin compiles.
     */
    @Parameter
    List<File> sourceRoots;

    @Override
    public void execute() throws MojoExecutionException {
        // A file left by an earlier build would point this build's exceptions at that build's
        // sources, so it goes first, and a skipped or failed run leaves no id behind.
        deleteStaleMetaProperties();

        if (skip) {
            getLog().info("Skipping the PostHog source upload (posthog.skip is true)");
            return;
        }

        List<File> roots = existingSourceRoots();
        if (roots.isEmpty()) {
            getLog().info("Skipping the PostHog source upload: none of the source roots exist");
            return;
        }

        boolean isWindows = PostHogCli.isWindows();
        String executable =
                PostHogCli.resolve(
                        cliExecutable,
                        System.getenv(),
                        new File(System.getProperty("user.home")),
                        isWindows);
        if (executable == null) {
            skipOrFail("posthog-cli was not found" + INSTALL_HINT, null);
            return;
        }

        Process process;
        try {
            // A posthog-cli from before `jvm upload` exits with an error on the unknown
            // subcommand, which would fail the build even when the upload is optional.
            if (!supportsJvmUpload(executable, isWindows)) {
                skipOrFail(
                        "posthog-cli ("
                                + executable
                                + ") is too old: it has no jvm upload command"
                                + UPDATE_HINT,
                        null);
                return;
            }
            process = start(PostHogCli.commandLine(executable, cliArguments(roots), isWindows));
        } catch (IOException e) {
            skipOrFail("Could not run posthog-cli (" + executable + ")" + INSTALL_HINT, e);
            return;
        }
        waitFor(process);

        File written = new File(outputDirectory, META_PROPERTIES_FILE);
        if (!written.isFile()) {
            if (requireUpload) {
                throw new MojoExecutionException(
                        "posthog-cli exited successfully but did not write " + written);
            }
            // posthog-cli skips the upload when it finds no credentials, and logs why.
            getLog().warn(
                    "The PostHog source upload was skipped, so exceptions from this build show no"
                            + " source code. Set posthog.requireUpload to fail the build instead.");
            return;
        }
        registerResourceDirectory();
    }

    List<String> cliArguments(List<File> roots) {
        List<String> args = new ArrayList<>();
        // A global option, so it goes before the subcommand.
        if (dotenvFile != null) {
            args.add("--dotenv-file");
            args.add(dotenvFile.getAbsolutePath());
        }
        args.add("jvm");
        args.add("upload");
        for (File root : roots) {
            args.add("--source");
            args.add(root.getAbsolutePath());
        }
        args.add("--meta-out");
        args.add(outputDirectory.getAbsolutePath());
        if (!requireUpload) {
            args.add("--skip-without-credentials");
        }
        if (isNotBlank(releaseName)) {
            args.add("--release-name");
            args.add(releaseName);
        }
        if (isNotBlank(releaseVersion)) {
            args.add("--release-version");
            args.add(releaseVersion);
        }
        return args;
    }

    private List<File> existingSourceRoots() {
        Set<File> configured = new LinkedHashSet<>();
        if (sourceRoots != null && !sourceRoots.isEmpty()) {
            configured.addAll(sourceRoots);
        } else {
            for (String root : project.getCompileSourceRoots()) {
                configured.add(new File(root).getAbsoluteFile());
            }
            configured.addAll(kotlinSourceDirs());
        }

        List<File> existing = new ArrayList<>();
        for (File root : configured) {
            if (root.isDirectory()) {
                existing.add(root);
            } else {
                getLog().debug("Skipping missing source root " + root);
            }
        }
        return existing;
    }

    /**
     * The directories that kotlin-maven-plugin compiles from its {@code <sourceDirs>}. A project
     * with Java and Kotlin sources usually lists {@code src/main/kotlin} there, as the Kotlin docs
     * show, and that list never becomes a compile source root of the project.
     */
    private List<File> kotlinSourceDirs() {
        List<File> dirs = new ArrayList<>();
        Plugin kotlin = project.getPlugin(KOTLIN_MAVEN_PLUGIN);
        if (kotlin == null) {
            return dirs;
        }
        addSourceDirs(kotlin.getConfiguration(), dirs);
        for (PluginExecution execution : kotlin.getExecutions()) {
            if (execution.getGoals().contains("compile")) {
                addSourceDirs(execution.getConfiguration(), dirs);
            }
        }
        return dirs;
    }

    private void addSourceDirs(Object configuration, List<File> dirs) {
        if (!(configuration instanceof Xpp3Dom)) {
            return;
        }
        Xpp3Dom sourceDirs = ((Xpp3Dom) configuration).getChild("sourceDirs");
        if (sourceDirs == null) {
            return;
        }
        for (Xpp3Dom sourceDir : sourceDirs.getChildren()) {
            String value = sourceDir.getValue();
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            File dir = new File(value.trim());
            dirs.add(dir.isAbsolute() ? dir : new File(project.getBasedir(), value.trim()));
        }
    }

    private void deleteStaleMetaProperties() throws MojoExecutionException {
        List<File> stale = new ArrayList<>();
        stale.add(new File(outputDirectory, META_PROPERTIES_FILE));
        // process-resources copies a resource only when the copy is missing or older, so the copy
        // in the classes directory has to go too. A posthog-meta.properties the project keeps in
        // its own resources is copied back right after this.
        if (project.getBuild() != null && project.getBuild().getOutputDirectory() != null) {
            stale.add(new File(project.getBuild().getOutputDirectory(), META_PROPERTIES_FILE));
        }
        for (File file : stale) {
            if (file.exists() && !file.delete()) {
                throw new MojoExecutionException("Could not delete the stale " + file);
            }
        }
    }

    private void skipOrFail(String message, Exception cause) throws MojoExecutionException {
        if (requireUpload) {
            throw new MojoExecutionException(message, cause);
        }
        getLog().warn(
                message
                        + " Skipping the PostHog source upload, so exceptions from this build show"
                        + " no source code. Set posthog.requireUpload to fail the build instead.");
    }

    /**
     * Whether this posthog-cli has {@code jvm upload} with every option the plugin passes. It asks
     * for the command's help, which needs no credentials and sends nothing to PostHog.
     */
    private boolean supportsJvmUpload(String executable, boolean isWindows)
            throws IOException, MojoExecutionException {
        Process process =
                start(
                        PostHogCli.commandLine(
                                executable, Arrays.asList("jvm", "upload", "--help"), isWindows));
        StringBuilder help = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                help.append(line).append('\n');
            }
            return process.waitFor() == 0 && help.toString().contains("--skip-without-credentials");
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for posthog-cli", e);
        }
    }

    private Process start(List<String> command) throws IOException {
        getLog().debug("Running " + String.join(" ", command));

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (project.getBasedir() != null) {
            // posthog-cli reads the release's git details from its working directory.
            builder.directory(project.getBasedir());
        }
        return builder.start();
    }

    private void waitFor(Process process) throws MojoExecutionException {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                getLog().info("[posthog-cli] " + line);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new MojoExecutionException("posthog-cli exited with code " + exitCode);
            }
        } catch (IOException e) {
            process.destroy();
            throw new MojoExecutionException("Could not read the posthog-cli output", e);
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for posthog-cli", e);
        }
    }

    private void registerResourceDirectory() {
        String directory = outputDirectory.getAbsolutePath();
        for (Resource resource : project.getResources()) {
            if (resource.getDirectory() != null
                    && new File(resource.getDirectory()).getAbsolutePath().equals(directory)) {
                return;
            }
        }

        Resource resource = new Resource();
        resource.setDirectory(directory);
        resource.addInclude(META_PROPERTIES_FILE);
        project.addResource(resource);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
