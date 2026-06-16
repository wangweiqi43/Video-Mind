package com.videomind.module.task.dto;

import com.videomind.common.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnalyzeTaskCreateResponse {

    private Long taskId;
    private TaskStatus status;
    private Boolean reused;
}

