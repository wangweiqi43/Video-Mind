package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "videomind.agent.workflow")
public class AgentWorkflowProperties {
    private long plannerTimeoutMillis = 30_000;
    private long executorTimeoutMillis = 40_000;
    private long criticTimeoutMillis = 30_000;
    private int maxToolCalls = 6;
    private int maxReplans = 1;
    private int maxCriticCandidates = 12;
    private int maxAcceptedEvidence = 6;
    private int maxEvidenceChars = 1_200;
}
