package com.videomind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.ffmpeg")
public class FfmpegProperties {

    private String mode = "ffmpeg";
    private String binaryPath = "ffmpeg";
    private String probeBinaryPath = "ffprobe";
    private String workDir = "runtime/ffmpeg";
}

