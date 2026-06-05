package com.cronas.engine.service;

import com.cronas.engine.entity.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WebhookExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookExecutor.class);
    private final RestClient restClient;

    public WebhookExecutor(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public boolean fireWebhook(Job job) {
        log.info("Firing webhook for Job ID: {} targeting {}", job.getJobId(), job.getTargetUrl());

        try {
            restClient.method(org.springframework.http.HttpMethod.valueOf(job.getHttpMethod()))
                    .uri(job.getTargetUrl())
                    .header("Content-Type", "application/json")
                    .body(job.getPayload())
                    .retrieve()
                    .toBodilessEntity(); // Expects a 2xx success status
            
            return true; // Success!
            
        } catch (Exception e) {
            log.error("Webhook failed for Job ID: {}. Error: {}", job.getJobId(), e.getMessage());
            return false; // Failed!
        }
    }
}