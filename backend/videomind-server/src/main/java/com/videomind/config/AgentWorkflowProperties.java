package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "videomind.agent.workflow")
public class AgentWorkflowProperties {
    private long decisionTimeoutMillis = 10_000;
}
