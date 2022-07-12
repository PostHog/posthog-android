package com.posthog.android;

import static com.posthog.android.internal.Utils.closeQuietly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PostHogFeatureFlags {
    private PostHog posthog;
    private Boolean overrideWarning;
    private Map<String, Boolean> flagCallReported;
    private Boolean reloadFeatureFlagsQueued;
    private Boolean reloadFeatureFlagsInAction;
    private String $anon_distinct_id;
    private final Logger logger;

    private PostHogFeatureFlags(
            PostHog posthog,
            Boolean overrideWarning,
            Map<String, Boolean> flagCallReported,
            Boolean reloadFeatureFlagsQueued,
            Boolean reloadFeatureFlagsInAction,
            String $anon_distinct_id,
            Logger logger
    ) {
        this.posthog = posthog;
        this.overrideWarning = overrideWarning || false;
        this.flagCallReported = flagCallReported;
        this.reloadFeatureFlagsQueued = reloadFeatureFlagsQueued || false;
        this.reloadFeatureFlagsInAction = reloadFeatureFlagsInAction || false;
        this.$anon_distinct_id = $anon_distinct_id;
        this.logger = logger;
    }

    public List<String> getFlags() {
        return new ArrayList<String>(this.getFlagVariants().keySet());
    }

    public Map<String, Object> getFlagVariants() {
    }

    public String getFeatureFlag() {
    }

    public Boolean isFeatureEnabled() {
    }

    public Object override() {
    }

    public void reloadFeatureFlags() {
    }

    private void startReloadTimer() {
        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
            // Your code here executes after 5 seconds!
        });
    }

    private void setReloadingPaused(Boolean isPaused) {
        this.reloadFeatureFlagsInAction = isPaused;
    }

    private void resetRequestQueue() {
        this.reloadFeatureFlagsQueued = false;
    }

    private void reloadFeatureFlagsRequest() {
        this.setReloadingPaused(true);
        String token = this.posthog.apiKey;
        String distinctId = this.posthog.propertiesCache.get().distinctId();


        logger.verbose(" in queue.");
        int payloadsUploaded = 0;
        Client.Connection connection = null;
        try {
            // Open a connection.
            connection = client.batch();

            // Write the payloads into the OutputStream.
            PostHogIntegration.BatchPayloadWriter writer =
                    new PostHogIntegration.BatchPayloadWriter(connection.os) //
                            .beginObject() //
                            .writeApiKey(client.apiKey)
                            .beginBatchArray();
            PostHogIntegration.PayloadWriter payloadWriter = new PostHogIntegration.PayloadWriter(writer, crypto);
            payloadQueue.forEach(payloadWriter);
            writer.endBatchArray().endObject().close();
            // Don't use the result of QueueFiles#forEach, since we may not upload the last element.
            payloadsUploaded = payloadWriter.payloadCount;

            // Upload the payloads.
            connection.close();
        } catch (Client.HTTPException e) {
            if (e.is4xx() && e.responseCode != 429) {
                // Simply log and proceed to remove the rejected payloads from the queue.
                logger.error(e, "Payloads were rejected by server. Marked for removal.");
                try {
                    payloadQueue.remove(payloadsUploaded);
                } catch (IOException e1) {
                    logger.error(e, "Unable to remove " + payloadsUploaded + " payload(s) from queue.");
                }
                return;
            } else {
                logger.error(e, "Error while uploading payloads");
                return;
            }
        } catch (IOException e) {
            logger.error(e, "Error while uploading payloads");
            return;
        } finally {
            closeQuietly(connection);
        }
    }

    public static class Builder {
        private PostHog posthog;
        private Boolean overrideWarning;
        private Map<String, Boolean> flagCallReported;
        private Boolean reloadFeatureFlagsQueued;
        private Boolean reloadFeatureFlagsInAction;
        private String $anon_distinct_id;
        private Logger logger;

        public Builder() {}

        public Builder posthog(PostHog posthog) {
            this.posthog = posthog;
            return this;
        }

        Builder overrideWarning(Boolean overrideWarning) {
            this.overrideWarning = overrideWarning;
            return this;
        }

        Builder flagCallReported(Map<String, Boolean> flagCallReported) {
            this.flagCallReported = flagCallReported;
            return this;
        }

        Builder reloadFeatureFlagsQueued(Boolean reloadFeatureFlagsQueued) {
            this.reloadFeatureFlagsQueued = reloadFeatureFlagsQueued;
            return this;
        }

        Builder reloadFeatureFlagsInAction(Boolean reloadFeatureFlagsInAction) {
            this.reloadFeatureFlagsInAction = reloadFeatureFlagsInAction;
            return this;
        }

        Builder $anon_distinct_id(String $anon_distinct_id) {
            this.$anon_distinct_id = $anon_distinct_id;
            return this;
        }

        Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public PostHogFeatureFlags build() {
            return new PostHogFeatureFlags(
                    posthog,
                    overrideWarning,
                    flagCallReported,
                    reloadFeatureFlagsQueued,
                    reloadFeatureFlagsInAction,
                    $anon_distinct_id,
                    logger
            );
        }
    }
}
