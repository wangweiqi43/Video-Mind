package com.videomind.module.task.dto;

import com.videomind.common.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskResultResponse {

    private Long taskId;
    private Long videoId;
    private TaskStatus status;
    private String transcriptionText;
    private String summaryText;
    private String summaryJson;
}

