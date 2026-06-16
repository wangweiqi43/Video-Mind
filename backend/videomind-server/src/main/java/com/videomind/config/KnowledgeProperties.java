package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.knowledge")
public class KnowledgeProperties {

    private String indexName = "idx:videomind_knowledge";
    private String keyPrefix = "knowledge:chunk:";
    private String taskStatusPrefix = "knowledge:task:";
    private Integer embeddingDim = 64;
    private Integer chunkSize = 600;
    private Integer chunkOverlap = 80;
    private Long ttlSeconds = 86400L;
}
