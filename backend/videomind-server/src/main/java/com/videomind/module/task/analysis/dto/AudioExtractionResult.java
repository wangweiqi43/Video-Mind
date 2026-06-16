package com.videomind.module.task.analysis.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AudioExtractionResult {

    private String audioPath;
    private Integer durationSeconds;
}

