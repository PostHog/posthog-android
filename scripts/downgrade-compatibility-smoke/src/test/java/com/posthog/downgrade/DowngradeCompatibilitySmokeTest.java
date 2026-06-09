package com.posthog.downgrade;

import android.app.Application;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.posthog.PostHog;
import com.posthog.android.PostHogAndroid;
import com.posthog.android.PostHogAndroidConfig;
import com.posthog.internal.PostHogPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DowngradeCompatibilitySmokeTest {
    private static final String DEFAULT_API_KEY = "downgrade_compatibility_project";
    private static final String HOST = "http://127.0.0.1:9";
    private static final String WRITER_DISTINCT_ID = "downgrade-compatibility-user";
    private static final Type PREFERENCES_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
    private Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler;

    @Before
    public void setUp() {
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            uncaught.add(throwable);
            if (previousUncaughtExceptionHandler != null) {
                previousUncaughtExceptionHandler.uncaughtException(thread, throwable);
            }
        });
        closePostHog();
    }

    @After
    public void tearDown() {
        closePostHog();
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler);
    }

    @Test
    public void persistedStateSurvivesDowngradedSdkStartup() throws Exception {
        String mode = System.getProperty("posthog.smoke.mode", "read");
        if ("write".equals(mode)) {
            writeCurrentSdkState();
        } else if ("read".equals(mode)) {
            readDowngradedSdkState();
        } else {
            throw new IllegalArgumentException("Unknown smoke mode: " + mode);
        }
    }

    private void writeCurrentSdkState() throws Exception {
        setupSdk(10_000);
        assertSdkEnabled();

        invokePostHog("identify", WRITER_DISTINCT_ID, mapOf("source", "downgrade-compatibility"), null);
        invokePostHog("group", "organization", "posthog", mapOf("ci", true));
        invokePostHog("screen", "Downgrade Compatibility", mapOf("source", "current-sdk"));

        for (int index = 0; index < 5; index++) {
            invokePostHog(
                "capture",
                "downgrade compatibility event",
                null,
                mapOf("index", index, "source", "current-sdk"),
                null,
                null,
                null,
                null
            );
        }

        for (int index = 0; index < 2; index++) {
            invokePostHog(
                "capture",
                "$snapshot",
                null,
                snapshotProperties(index),
                null,
                null,
                null,
                null
            );
        }

        captureLogsRequired();

        waitForFileCount("analytics event queue", eventsDir(), 5);
        waitForFileCount("replay snapshot queue", replayDir(), 2);
        waitForFileCount("logs queue", logsDir(), 2);

        FileBackedPreferences preferences = new FileBackedPreferences(preferencesFile());
        assertEquals(WRITER_DISTINCT_ID, preferences.getValue("distinctId", null));
        assertNoUncaughtExceptions();
        System.out.println("Wrote downgrade compatibility state in " + stateDir().getAbsolutePath());
    }

    private void readDowngradedSdkState() throws Exception {
        setupSdk(1);
        assertSdkEnabled();
        assertEquals(WRITER_DISTINCT_ID, invokePostHog("distinctId"));

        invokePostHog(
            "capture",
            "downgrade compatibility read smoke",
            null,
            mapOf("source", "downgraded-sdk"),
            null,
            null,
            null,
            null
        );
        invokePostHog("flush");

        waitForBackgroundQueueExceptions();
        System.out.println("Downgraded SDK started against state in " + stateDir().getAbsolutePath());
    }

    private void setupSdk(int flushAt) throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        PostHogAndroidConfig config = newConfig();

        invokeSetter(config, "setDebug", boolean.class, true);
        invokeSetter(config, "setFlushAt", int.class, flushAt);
        invokeSetter(config, "setMaxBatchSize", int.class, 10_000);
        invokeSetter(config, "setMaxQueueSize", int.class, 10_000);
        invokeSetter(config, "setFlushIntervalSeconds", int.class, 10_000);
        invokeSetter(config, "setPreloadFeatureFlags", boolean.class, false);
        invokeSetter(config, "setRemoteConfig", boolean.class, false);
        invokeSetter(config, "setCaptureApplicationLifecycleEvents", boolean.class, false);
        invokeSetter(config, "setCaptureDeepLinks", boolean.class, false);
        invokeSetter(config, "setCaptureScreenViews", boolean.class, false);
        invokeSetter(config, "setSessionReplay", boolean.class, false);
        invokeSetter(config, "setSurveys", boolean.class, false);

        invokeSetter(config, "setLegacyStoragePrefix", String.class, legacyDir().getAbsolutePath());
        invokeSetter(config, "setStoragePrefix", String.class, eventsRootDir().getAbsolutePath());
        invokeSetter(config, "setReplayStoragePrefix", String.class, replayRootDir().getAbsolutePath());
        invokeSetter(config, "setLogsStoragePrefix", String.class, logsRootDir().getAbsolutePath());
        invokeSetter(config, "setCachePreferences", PostHogPreferences.class, new FileBackedPreferences(preferencesFile()));

        PostHogAndroid.Companion.setup(application, config);
    }

    private static PostHogAndroidConfig newConfig() throws Exception {
        try {
            Constructor<PostHogAndroidConfig> constructor = PostHogAndroidConfig.class.getConstructor(String.class, String.class);
            return constructor.newInstance(apiKey(), HOST);
        } catch (NoSuchMethodException ignored) {
            return new PostHogAndroidConfig(apiKey());
        }
    }

    private static void invokeSetter(Object target, String methodName, Class<?> parameterType, Object value) throws Exception {
        try {
            Method method = target.getClass().getMethod(methodName, parameterType);
            method.invoke(target, value);
        } catch (NoSuchMethodException ignored) {
            // Older pinned SDKs may not have newer config knobs. The smoke test only needs the
            // knobs that exist on that version to be set safely.
        }
    }

    private static Object invokePostHog(String methodName, Object... args) throws Exception {
        Object companion = PostHog.Companion;
        for (Method method : companion.getClass().getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                return method.invoke(companion, args);
            }
        }
        throw new NoSuchMethodException("PostHog." + methodName + " with " + args.length + " argument(s)");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void captureLogsRequired() throws Exception {
        try {
            Class<?> severityClass = Class.forName("com.posthog.logs.PostHogLogSeverity");
            Object warn = Enum.valueOf((Class<Enum>) severityClass.asSubclass(Enum.class), "WARN");
            Object error = Enum.valueOf((Class<Enum>) severityClass.asSubclass(Enum.class), "ERROR");

            invokePostHog(
                "captureLog",
                "downgrade compatibility warning log",
                warn,
                mapOf("source", "current-sdk", "index", 0),
                null,
                null,
                null
            );
            invokePostHog(
                "captureLog",
                "downgrade compatibility error log",
                error,
                mapOf("source", "current-sdk", "index", 1),
                null,
                null,
                null
            );
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new AssertionError("Current SDK did not expose the expected captureLog API", e);
        }
    }

    private static void closePostHog() {
        try {
            invokePostHog("close");
        } catch (Throwable ignored) {
        }
    }

    private static void assertSdkEnabled() throws Exception {
        assertFalse("PostHog should be enabled after setup", (Boolean) invokePostHog("isOptOut"));
    }

    private void waitForBackgroundQueueExceptions() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            assertNoUncaughtExceptions();
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertNoUncaughtExceptions();
    }

    private void assertNoUncaughtExceptions() {
        if (uncaught.isEmpty()) {
            return;
        }

        AssertionError assertionError = new AssertionError("Uncaught SDK exception on background thread");
        synchronized (uncaught) {
            for (Throwable throwable : uncaught) {
                assertionError.addSuppressed(throwable);
            }
        }
        throw assertionError;
    }

    private static Map<String, Object> snapshotProperties(int index) {
        Map<String, Object> snapshotData = new LinkedHashMap<>();
        snapshotData.put("type", 4);
        snapshotData.put("data", mapOf("href", "http://example.com/" + index));

        return mapOf(
            "$session_id", "downgrade-compatibility-session",
            "$snapshot_data", snapshotData
        );
    }

    private static Map<String, Object> mapOf(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static void waitForFileCount(String description, File directory, int minimumCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int count;
        do {
            count = countFiles(directory);
            if (count >= minimumCount) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);

        fail("Expected at least " + minimumCount + " file(s) in " + description + " at " + directory
            + ", found " + countFiles(directory) + ". State tree:\n" + describeTree(stateDir(), ""));
    }

    private static int countFiles(File directory) {
        File[] files = directory.listFiles(File::isFile);
        return files == null ? 0 : files.length;
    }

    private static String describeTree(File file, String indent) {
        if (!file.exists()) {
            return indent + file.getAbsolutePath() + " (missing)\n";
        }
        StringBuilder builder = new StringBuilder(indent).append(file.getName()).append('\n');
        File[] children = file.listFiles();
        if (children != null) {
            Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
            for (File child : children) {
                builder.append(describeTree(child, indent + "  "));
            }
        }
        return builder.toString();
    }

    private static String apiKey() {
        return System.getProperty("posthog.smoke.apiKey", DEFAULT_API_KEY);
    }

    private static File stateDir() {
        String path = System.getProperty("posthog.smoke.stateDir");
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalStateException("posthog.smoke.stateDir is required");
        }
        File dir = new File(path);
        assertTrue("Could not create state dir " + dir, dir.exists() || dir.mkdirs());
        return dir;
    }

    private static File preferencesFile() {
        return new File(stateDir(), "preferences.json");
    }

    private static File legacyDir() {
        return new File(stateDir(), "legacy");
    }

    private static File eventsRootDir() {
        return new File(stateDir(), "events");
    }

    private static File replayRootDir() {
        return new File(stateDir(), "replay");
    }

    private static File logsRootDir() {
        return new File(stateDir(), "logs");
    }

    private static File eventsDir() {
        return new File(eventsRootDir(), apiKey());
    }

    private static File replayDir() {
        return new File(replayRootDir(), apiKey());
    }

    private static File logsDir() {
        return new File(logsRootDir(), apiKey());
    }

    public static final class FileBackedPreferences implements PostHogPreferences {
        private static final Set<String> INTERNAL_KEYS = new HashSet<>(Arrays.asList(
            "groups",
            "anonymousId",
            "distinctId",
            "isIdentified",
            "personProcessingEnabled",
            "opt-out",
            "flags",
            "featureFlags",
            "featureFlagsPayload",
            "feature_flag_request_id",
            "feature_flag_evaluated_at",
            "sessionReplay",
            "surveys",
            "errorTracking",
            "capturePerformance",
            "personPropertiesForFlags",
            "groupPropertiesForFlags",
            "surveySeen",
            "lastSeenSurveyDate",
            "version",
            "build",
            "deviceId",
            "stringifiedKeys"
        ));

        private final File file;
        private final Gson gson = new Gson();

        FileBackedPreferences(File file) {
            this.file = file;
        }

        @Override
        public synchronized Object getValue(String key, Object defaultValue) {
            Object value = read().get(key);
            return value == null ? defaultValue : value;
        }

        @Override
        public synchronized void setValue(String key, Object value) {
            Map<String, Object> values = read();
            values.put(key, value);
            write(values);
        }

        @Override
        public synchronized void clear(List<String> except) {
            Map<String, Object> values = read();
            values.keySet().removeIf(key -> !except.contains(key));
            write(values);
        }

        @Override
        public synchronized void remove(String key) {
            Map<String, Object> values = read();
            values.remove(key);
            write(values);
        }

        @Override
        public synchronized Map<String, Object> getAll() {
            Map<String, Object> values = new HashMap<>(read());
            values.keySet().removeIf(INTERNAL_KEYS::contains);
            return values;
        }

        private Map<String, Object> read() {
            if (!file.exists()) {
                return new LinkedHashMap<>();
            }
            try (FileReader reader = new FileReader(file)) {
                Map<String, Object> values = gson.fromJson(reader, PREFERENCES_TYPE);
                return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read preferences from " + file, e);
            }
        }

        private void write(Map<String, Object> values) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Failed to create preferences directory " + parent);
            }
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(values, writer);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write preferences to " + file, e);
            }
        }
    }
}
