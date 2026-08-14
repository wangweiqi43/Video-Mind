package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.ai.tencent-asr")
public class TencentAsrProperties {
    private String endpoint = "https://asr.tencentcloudapi.com";
    private String secretId;
    private String secretKey;
    private String region = "ap-shanghai";
    private String engineModelType = "16k_zh_en_2.0";
    private int pollIntervalMillis = 1_000;
    private int timeoutSeconds = 600;
    private int presignedUrlExpirySeconds = 900;
}
