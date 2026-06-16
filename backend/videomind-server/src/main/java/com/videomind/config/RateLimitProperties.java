package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.rate-limit")
public class RateLimitProperties {

    private Long uploadPermitsPerMinute = 60L;
    private Long analyzePermitsPerMinute = 30L;
    private Long ttlSeconds = 600L;
}
