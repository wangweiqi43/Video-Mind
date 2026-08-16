package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.ocr")
public class OcrProperties {
    private String mode = "local";
    private String endpoint = "http://127.0.0.1:8868/ocr";
    private double sceneThreshold = 0.30;
    private int maxIntervalSeconds = 30;
    private int maxFrames = 300;
    private int timeoutSeconds = 60;
}
