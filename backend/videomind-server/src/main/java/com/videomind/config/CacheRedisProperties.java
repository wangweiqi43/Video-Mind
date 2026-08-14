package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.cache-redis")
public class CacheRedisProperties {
    private String host = "localhost";
    private int port = 6382;
    private int database = 0;
    private String password;
    private long contextTtlSeconds = 7200;
}
