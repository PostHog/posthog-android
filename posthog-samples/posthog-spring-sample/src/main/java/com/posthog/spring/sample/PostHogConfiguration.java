package com.posthog.spring.sample;

import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;
import com.posthog.server.PostHog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PostHogConfiguration {
    @Value("${posthog.api.key:phc_YOUR_API_KEY_HERE}")
    private String apiKey;

    @Value("${posthog.host:https://us.i.posthog.com}")
    private String host;

    @Bean(destroyMethod = "close")
    public PostHogInterface posthog() {
        PostHogConfig config = PostHogConfig
                .builder(this.apiKey)
                .host(this.host)
                .build();

        return PostHog.with(config);
    }
}
