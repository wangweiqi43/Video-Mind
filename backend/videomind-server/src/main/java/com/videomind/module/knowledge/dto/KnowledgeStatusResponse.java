package com.videomind.module.knowledge.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeStatusResponse {

    private Long taskId;
    private Boolean vectorized;
    private String status;
    private String message;
    private Integer chunkCount;
    private String updatedTime;
}
