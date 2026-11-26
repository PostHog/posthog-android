package com.posthog.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Finds posthog-cli and builds the command line that runs it. */
final class PostHogCli {
    static final String DEFAULT_EXECUTABLE = "posthog-cli";

    private static final String CMD_UNSAFE_CHARACTERS = "&|<>^%!\"\r\n";

    private PostHogCli() {}

    /**
     * Returns the posthog-cli to run, or null when none is installed. A configured path is used
     * verbatim. Otherwise this checks {@code ~/.posthog/posthog-cli} first, the override location
     * PostHog's Xcode build script also checks first, then PATH, then the common install
     * locations (Homebrew, the installer script's {@code ~/.cargo/bin}) that a build launched from
     * an IDE may not have on PATH.
     */
    static String resolve(
            String configured, Map<String, String> environment, File home, boolean isWindows) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }

        if (isWindows) {
            File exe = findOnPath(DEFAULT_EXECUTABLE + ".exe", environment, true);
            if (exe != null) {
                return exe.getAbsolutePath();
            }
            File shim = findOnPath(DEFAULT_EXECUTABLE + ".cmd", environment, true);
            if (shim == null) {
                return null;
            }
            // The npm shim only starts the native binary that the package installed. Running that
            // binary directly keeps the arguments away from cmd, which would run a `&` in a path
            // or a release version as a second command.
            File npmExe = npmInstalledExe(shim);
            // Without it, cmd finds the shim by its bare name.
            return npmExe != null ? npmExe.getAbsolutePath() : DEFAULT_EXECUTABLE;
        }

        File installed = new File(home, ".posthog/" + DEFAULT_EXECUTABLE);
        if (isRunnable(installed)) {
            return installed.getAbsolutePath();
        }

        File onPath = findOnPath(DEFAULT_EXECUTABLE, environment, false);
        if (onPath != null) {
            return onPath.getAbsolutePath();
        }

        List<File> knownLocations =
                Arrays.asList(
                        new File("/opt/homebrew/bin/" + DEFAULT_EXECUTABLE),
                        new File("/usr/local/bin/" + DEFAULT_EXECUTABLE),
                        new File(home, ".cargo/bin/" + DEFAULT_EXECUTABLE),
                        new File(home, ".local/bin/" + DEFAULT_EXECUTABLE));
        for (File candidate : knownLocations) {
            if (isRunnable(candidate)) {
                return candidate.getAbsolutePath();
            }
        }

        return null;
    }

    /**
     * On Windows a bare name may be the npm posthog-cli.cmd shim, which only cmd can find and run.
     * An .exe runs directly, which also keeps cmd from mangling a quoted path with spaces.
     */
    static List<String> commandLine(String executable, List<String> args, boolean isWindows) {
        List<String> command = new ArrayList<>();
        if (runsThroughCmd(executable, isWindows)) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(args);
        return command;
    }

    static boolean runsThroughCmd(String executable, boolean isWindows) {
        return isWindows && !executable.toLowerCase().endsWith(".exe");
    }

    /**
     * The first of {@code values} that cmd would not pass through unchanged, or null when all are
     * safe. Java quotes an argument with spaces, but cmd still reads operators such as {@code &}
     * and {@code |}, expands {@code %VAR%}, and ends the command at a line break. Parentheses are
     * allowed, because they start no command, and versions such as {@code 1.0(5)} hold them. The
     * Gradle plugin uses the same characters.
     */
    static String firstUnsafeForCmd(List<String> values) {
        for (String value : values) {
            for (int i = 0; i < value.length(); i++) {
                if (CMD_UNSAFE_CHARACTERS.indexOf(value.charAt(i)) >= 0) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * The PATH for the CLI process, with the CLI's directory and node's directory first when PATH
     * lacks them. An npm install of posthog-cli is a {@code #!/usr/bin/env node} launcher, and a
     * build started from an IDE may have neither the launcher's directory nor node on its PATH.
     * Returns null when the PATH needs no change.
     */
    static String launcherPath(
            String executable,
            Map<String, String> environment,
            File home,
            boolean isWindows,
            List<File> knownNodeLocations) {
        String path = pathValue(environment, isWindows);
        List<String> onPath =
                path == null ? new ArrayList<>() : Arrays.asList(path.split(File.pathSeparator));
        List<String> missing = new ArrayList<>();
        File executableFile = new File(executable);
        if (executableFile.isAbsolute() && executableFile.getParent() != null) {
            missing.add(executableFile.getParent());
        }
        File node = resolveNode(environment, home, isWindows, knownNodeLocations);
        if (node != null) {
            missing.add(node.getParent());
        }
        missing.removeAll(onPath);
        if (missing.isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>(new LinkedHashSet<>(missing));
        if (path != null && !path.isEmpty()) {
            entries.add(path);
        }
        return String.join(File.pathSeparator, entries);
    }

    /** node on PATH, else (outside Windows) in nvm, Homebrew and the other common locations. */
    static File resolveNode(
            Map<String, String> environment,
            File home,
            boolean isWindows,
            List<File> knownLocations) {
        File onPath = findOnPath(isWindows ? "node.exe" : "node", environment, isWindows);
        if (onPath != null || isWindows) {
            return onPath;
        }
        List<File> candidates = knownLocations;
        if (candidates == null) {
            candidates = new ArrayList<>(nvmNodes(new File(home, ".nvm/versions/node")));
            File[] cellars = new File("/opt/homebrew/Cellar/nvm").listFiles();
            if (cellars != null) {
                for (File cellar : cellars) {
                    candidates.addAll(nvmNodes(new File(cellar, "versions/node")));
                }
            }
            candidates.add(new File("/opt/homebrew/bin/node"));
            candidates.add(new File("/usr/local/bin/node"));
            candidates.add(new File(home, ".local/bin/node"));
        }
        for (File candidate : candidates) {
            if (isRunnable(candidate)) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }

    /**
     * The native binary of the npm package, which npm installs into
     * {@code node_modules/@posthog/cli/node_modules/.bin_real}. A global install puts the shim in
     * the npm prefix, next to {@code node_modules}. A project install puts it in {@code
     * node_modules/.bin}.
     */
    static File npmInstalledExe(File shim) {
        File shimDirectory = shim.getAbsoluteFile().getParentFile();
        String binary = "@posthog/cli/node_modules/.bin_real/" + DEFAULT_EXECUTABLE + ".exe";
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(shimDirectory, "node_modules/" + binary));
        if (shimDirectory.getParentFile() != null) {
            candidates.add(new File(shimDirectory.getParentFile(), binary));
        }
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The PATH key of {@code environment}. Windows spells it Path, and is case-insensitive. */
    static String pathKey(Map<String, String> environment, boolean isWindows) {
        for (String key : environment.keySet()) {
            if (isWindows ? key.equalsIgnoreCase("PATH") : key.equals("PATH")) {
                return key;
            }
        }
        return "PATH";
    }

    private static String pathValue(Map<String, String> environment, boolean isWindows) {
        return environment.get(pathKey(environment, isWindows));
    }

    private static File findOnPath(String name, Map<String, String> environment, boolean isWindows) {
        String path = pathValue(environment, isWindows);
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            File candidate = new File(dir, name);
            if (isRunnable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRunnable(File file) {
        return file.isFile() && file.canExecute();
    }

    /** {@code <root>/vX.Y.Z/bin/node}, newest version first. */
    private static List<File> nvmNodes(File root) {
        List<File> nodes = new ArrayList<>();
        File[] versions = root.listFiles();
        if (versions == null) {
            return nodes;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(versions));
        sorted.sort((a, b) -> compareVersions(versionParts(b.getName()), versionParts(a.getName())));
        for (File version : sorted) {
            nodes.add(new File(version, "bin/node"));
        }
        return nodes;
    }

    private static List<Long> versionParts(String name) {
        List<Long> parts = new ArrayList<>();
        for (String part : name.split("\\D+")) {
            if (!part.isEmpty()) {
                try {
                    parts.add(Long.parseLong(part));
                } catch (NumberFormatException e) {
                    parts.add(Long.MAX_VALUE);
                }
            }
        }
        return parts;
    }

    private static int compareVersions(List<Long> a, List<Long> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int cmp = Long.compare(i < a.size() ? a.get(i) : 0L, i < b.size() ? b.get(i) : 0L);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
