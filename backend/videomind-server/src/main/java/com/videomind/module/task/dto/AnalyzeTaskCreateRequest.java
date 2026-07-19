package com.videomind.module.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AnalyzeTaskCreateRequest {

    @NotNull
    private Long videoId;

    private Boolean autoVectorize = false;

    private String applicationMode = "NORMAL";
}

