package com.videomind.module.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PresentationCreateRequest {

    private String template = "professional";
    private String language = "zh-CN";
    @Min(3)
    @Max(15)
    private Integer slideCount = 10;
    private String audience = "general";
    private String tone = "concise";
}
