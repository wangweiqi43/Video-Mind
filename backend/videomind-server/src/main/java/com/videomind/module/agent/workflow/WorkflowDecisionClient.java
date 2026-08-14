package com.videomind.module.agent.workflow;

public interface WorkflowDecisionClient {
    String decide(String systemPrompt, String userPrompt);
}
