package com.videomind.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FfmpegProperties.class, OcrProperties.class})
public class FfmpegConfig {
}

