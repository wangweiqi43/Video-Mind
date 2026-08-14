package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.knowledge")
public class KnowledgeProperties {

    private Integer embeddingDim = 64;
    private Integer chunkSize = 600;
    private Integer chunkOverlap = 80;
}
