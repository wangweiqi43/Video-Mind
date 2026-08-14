package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.elasticsearch")
public class ElasticsearchProperties {
    private boolean enabled = true;
    private String url = "http://localhost:9201";
    private String indexAlias = "videomind-chunks";
    private String physicalIndex = "videomind-chunks-v1";
    private int dimension = 1024;
    private int numCandidates = 200;
    private String username;
    private String password;
    private int connectTimeoutMillis = 2_000;
    private int readTimeoutMillis = 60_000;
}
