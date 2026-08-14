package com.videomind.module.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AnalyzeTaskCreateRequest {

    @NotNull
    private Long videoId;

}

