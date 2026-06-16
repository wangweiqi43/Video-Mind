package com.videomind.module.task.analysis.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SummaryResult {

    private String summaryText;
    private String summaryJson;
    private String modelName;
}

