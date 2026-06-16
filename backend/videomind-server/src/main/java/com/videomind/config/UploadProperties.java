package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.upload")
public class UploadProperties {

    private String chunkWorkDir = "runtime/uploads";
    private String bitmapPrefix = "upload:bitmap:";
    private Long bitmapTtlSeconds = 86400L;
}
