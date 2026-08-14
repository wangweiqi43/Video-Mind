package com.videomind.module.task.analysis.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AsrResult {

    private String language;
    private String text;

    @Builder.Default
    private List<AsrSegmentResult> segments = List.of();
}

