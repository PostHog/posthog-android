package com.posthog.spring.sample;

import com.posthog.server.PostHogInterface;
import com.posthog.server.PostHogCaptureOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ActionController {

    private static final Logger logger = LoggerFactory.getLogger(ActionController.class);

    private final PostHogInterface postHog;

    public ActionController(PostHogInterface postHog) {
        this.postHog = postHog;
    }

    @PostMapping("/action")
    public ResponseEntity performAction() {
        // In practice, use a real, stable distinct ID
        // String distinctId = currentUser.getId();
        String distinctId = "user-" + System.currentTimeMillis();

        postHog.capture(
            distinctId,
            "action_performed",
            PostHogCaptureOptions
                .builder()
                .property("source", "api")
                .build()
        );

        return ResponseEntity.ok().build();
    }
}
