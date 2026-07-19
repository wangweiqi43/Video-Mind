package com.videomind.module.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdvancedReportResponse {
    private Long id;
    private Long videoId;
    private String agentTaskId;
    private String status;
    private Integer progress;
    private String stage;
    private String errorCode;
    private String errorMessage;
    private String reportId;
    private String reportKnowledgeBaseId;
    private String artifactId;
    private String downloadUrl;
    private String reportMarkdown;
    private JsonNode references;
    private Integer transcriptVersion;
    private Integer targetLength;
    private String outputProfile;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
