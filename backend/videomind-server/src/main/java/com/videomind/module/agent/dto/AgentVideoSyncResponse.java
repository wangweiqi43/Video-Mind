package com.videomind.module.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentVideoSyncResponse {

    private Long videoId;
    private Integer transcriptVersion;
    private String taskId;
    private String status;
    private String stage;
    private Integer progress;
    private String knowledgeBaseId;
    private String sourceKnowledgeBaseId;
    private String errorCode;
    private String errorMessage;
}
