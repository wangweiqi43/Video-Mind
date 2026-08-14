package com.videomind.module.agent.workflow;

import java.time.Instant;

public interface WorkflowClock {
    Instant now();
}
