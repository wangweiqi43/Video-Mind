package com.videomind.module.task.analysis.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsrResult {

    private String language;
    private String text;
}

