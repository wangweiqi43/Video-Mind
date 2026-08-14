package com.videomind.module.agent.workflow;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SystemWorkflowClock implements WorkflowClock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
