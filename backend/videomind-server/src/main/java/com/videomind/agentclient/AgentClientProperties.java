package com.videomind.agentclient;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.agent")
public class AgentClientProperties {

    private boolean enabled = false;
    private boolean ingestEnabled = false;
    private boolean chatEnabled = false;
    private boolean presentationEnabled = false;
    private boolean fallbackOnError = true;
    private String baseUrl = "http://localhost:8090";
    private String tenantId = "videomind";
    private String apiKey = "";
    private String signingSecret = "";
    private String webhookSecret = "";
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 180;
    private int maxRetries = 2;
    private int presignedUrlExpirySeconds = 900;
}
