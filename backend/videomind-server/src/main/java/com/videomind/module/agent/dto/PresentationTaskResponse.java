package com.videomind.module.agent.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresentationTaskResponse {

    private Long id;
    private Long videoId;
    private String agentTaskId;
    private String status;
    private Integer progress;
    private String errorCode;
    private String errorMessage;
    private String presentationId;
    private String downloadUrl;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
