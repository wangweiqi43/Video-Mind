package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.minio")
public class MinioProperties {

    private String endpoint;
    private String presignEndpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
}

