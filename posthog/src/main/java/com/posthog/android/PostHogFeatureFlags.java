package com.posthog.android;

import static com.posthog.android.Persistence.ENABLED_FEATURE_FLAGS_KEY;
import static com.posthog.android.internal.Utils.DEFAULT_FLAG_RELOAD_DEBOUNCE_INTERVAL;
import static com.posthog.android.internal.Utils.closeQuietly;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the caching, polling, and fetching of feature flags.
 */
public class PostHogFeatureFlags {
    private PostHog posthog;
    private Map<String, Boolean> flagCallReported;
    private Boolean reloadFeatureFlagsQueued;
    private Boolean reloadFeatureFlagsInAction;
    private Boolean featureFlagsLoaded;
    private final Logger logger;
    private final Client client;

    private PostHogFeatureFlags(
            PostHog posthog,
            Map<String, Boolean> flagCallReported,
            Boolean reloadFeatureFlagsQueued,
            Boolean reloadFeatureFlagsInAction,
            Logger logger,
            Client client
    ) {
        this.posthog = posthog;
        this.flagCallReported = flagCallReported == null ? new HashMap() : flagCallReported;
        this.reloadFeatureFlagsQueued = reloadFeatureFlagsQueued == null ? false : reloadFeatureFlagsQueued;
        this.reloadFeatureFlagsInAction = reloadFeatureFlagsInAction == null ? false : reloadFeatureFlagsInAction;
        this.featureFlagsLoaded = false;
        this.logger = logger;
        this.client = client;
    }

    public List<String> getFlags() {
        return new ArrayList<String>(this.getFlagVariants().keySet());
    }

    public ValueMap getFlagVariants() {
        Persistence persistence = this.posthog.persistenceCache.get();
        return persistence.enabledFeatureFlags();
    }

    public Object getFeatureFlag(final @NonNull String key, final @Nullable Object defaultValue, final @Nullable Map<String, Object> options) {
        if (!this.featureFlagsLoaded) {
            this.logger.error(null, "getFeatureFlag for key %s failed. Feature flags didn't load in time.", key);
            return defaultValue;
        }
        Object flagValue = this.getFlagVariants().get(key);
        if (options != null && (Boolean) options.get("send_event") && this.flagCallReported.get(key) == null) {
            this.flagCallReported.put(key, true);
            this.posthog.capture(
                    "$feature_flag_called",
                    new Properties()
                            .putValue("$feature_flag", key)
                            .putValue("$feature_flag_response", flagValue));
        }
        if (flagValue != null && flagValue != "") {
            return flagValue;
        }
        return defaultValue;
    }

    public Boolean isFeatureEnabled(final @NonNull String key, final @Nullable Boolean defaultValue, final @Nullable Map<String, Object> options) {
        if (!this.featureFlagsLoaded) {
            this.logger.error(null, "isFeatureEnabled for key %s failed. Feature flags didn't load in time.", key);
            return defaultValue;
        }
        Object value = this.getFeatureFlag(key, defaultValue, options);
        if (value != null) {
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            // Multivariate case
            return true;
        }
        return defaultValue;
    }

    /**
     * Reloads feature flags asynchronously.
     * <p>
     * Constraints:
     * <p>
     * 1. Avoid parallel requests
     * 2. Delay a few milliseconds after each reloadFeatureFlags call to batch subsequent changes together
     * 3. Called once when building PostHogFeatureFlags instance (see build method)
     */
    public void reloadFeatureFlags() {
        if (!this.reloadFeatureFlagsQueued) {
            this.reloadFeatureFlagsQueued = true;
            this.reloadFeatureFlagsWithDebounce();
        }
    }

    private void reloadFeatureFlagsWithDebounce() {
        if (this.reloadFeatureFlagsQueued && !this.reloadFeatureFlagsInAction) {
            new Thread(() -> {
                try {
                    Thread.sleep(DEFAULT_FLAG_RELOAD_DEBOUNCE_INTERVAL);
                    this.reloadFeatureFlagsRequest();
                } catch (Exception e) {
                    System.err.println(e);
                }
            }).start();
            this.reloadFeatureFlagsQueued = false;
        }
    }

    private void setReloadingPaused(Boolean isPaused) {
        this.reloadFeatureFlagsInAction = isPaused;
    }

    private void receivedFeatureFlags(HashMap response) {
        Map flags = (Map) response.get("featureFlags");
        Persistence persistence = this.posthog.persistenceCache.get();

        if (flags != null) {
            persistence.putEnabledFeatureFlags(flags);
        } else {
            persistence.put(ENABLED_FEATURE_FLAGS_KEY, null);
        }
    }

    protected void reloadFeatureFlagsRequest() {
        this.setReloadingPaused(true);

        logger.verbose(" reloading feature flags.");
        Properties properties = this.posthog.propertiesCache.get();
        Client.Connection connection = null;
        try {
            // Open a connection.
            connection = client.decide();
            HttpURLConnection con = connection.connection;


            // Construct payload
            JSONObject payload = new JSONObject();
            payload.put("token", this.posthog.apiKey);
            payload.put("distinct_id", properties.distinctId());
            if (properties.distinctId() == null) {
                // Use anon id if distinct id doesn't exist since decide requires a distinct id
                payload.put("distinct_id", properties.anonymousId());
            }
            payload.put("groups", properties.groups());
            payload.put("$anon_distinct_id", properties.anonymousId());
            String stringifiedPayload = payload.toString();

            // Write payload to output stream
            OutputStream os = con.getOutputStream();
            byte[] input = stringifiedPayload.getBytes("utf-8");
            os.write(input, 0, input.length);

            // Read response from input stream
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), "utf-8")
            );
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            HashMap<String, Object> mapResponse = new Gson().fromJson(response.toString(), HashMap.class);

            this.receivedFeatureFlags(mapResponse);

            // :TRICKY: Reload - start another request if queued!
            this.featureFlagsLoaded = true;
            this.reloadFeatureFlagsWithDebounce();

        } catch (IllegalArgumentException e) {
            logger.error(e, "Error while sending reload feature flags request");
            return;
        } catch (Client.HTTPException e) {
            logger.error(e, "Error while sending reload feature flags request");
            return;
        } catch (IOException e) {
            logger.error(e, "Error while sending reload feature flags request");
            return;
        } catch (JSONException e) {
            logger.error(e, "Error while creating payload");
            return;
        } finally {
            closeQuietly(connection);
            this.setReloadingPaused(false);
        }
    }


    public static class Builder {
        private PostHog posthog;
        private Logger logger;
        private Client client;

        public Builder() {
        }

        public Builder posthog(PostHog posthog) {
            this.posthog = posthog;
            return this;
        }

        Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        Builder client(Client client) {
            this.client = client;
            return this;
        }

        public PostHogFeatureFlags build() {
            PostHogFeatureFlags instance = new PostHogFeatureFlags(
                    posthog,
                    null,
                    null,
                    null,
                    logger,
                    client
            );
            if (posthog != null) {
                instance.reloadFeatureFlags();
            }
            return instance;
        }
    }
}
