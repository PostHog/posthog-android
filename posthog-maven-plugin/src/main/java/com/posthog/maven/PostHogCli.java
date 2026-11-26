package com.posthog.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Finds posthog-cli and builds the command line that runs it. */
final class PostHogCli {
    static final String DEFAULT_EXECUTABLE = "posthog-cli";

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
            // A native posthog-cli.exe runs directly. The npm posthog-cli.cmd shim runs only
            // through cmd, which finds it by the bare name.
            File exe = findOnPath(DEFAULT_EXECUTABLE + ".exe", environment, true);
            if (exe != null) {
                return exe.getAbsolutePath();
            }
            return findOnPath(DEFAULT_EXECUTABLE + ".cmd", environment, true) != null
                    ? DEFAULT_EXECUTABLE
                    : null;
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
        if (isWindows && !executable.toLowerCase().endsWith(".exe")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(args);
        return command;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static File findOnPath(String name, Map<String, String> environment, boolean isWindows) {
        String path = null;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            // Windows spells it Path, and environment lookups there are case-insensitive.
            if (isWindows ? entry.getKey().equalsIgnoreCase("PATH") : entry.getKey().equals("PATH")) {
                path = entry.getValue();
                break;
            }
        }
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
}
