package com.videomind.module.task.dto;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import java.time.LocalDateTime;

public record ProcessingTaskStatusResponse(
        Long taskId,
        ProcessingTaskType taskType,
        ProcessingTaskState state,
        String stage,
        String errorCode,
        String errorMessage,
        LocalDateTime createdTime,
        LocalDateTime updatedTime) {
}
