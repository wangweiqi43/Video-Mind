package com.videomind.module.task.mq;

import com.videomind.common.enums.ProcessingTaskType;
import java.util.Map;

public record TaskCreateCommand(
        Long userId,
        ProcessingTaskType taskType,
        Long businessId,
        String businessFingerprint,
        String initialStage,
        int maxAttempts,
        Map<String, Object> payload) {
}
