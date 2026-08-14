package com.videomind.module.task.analysis.dto;

public record AsrSegmentResult(
        long startMs,
        long endMs,
        String text,
        Integer speakerId
) {
}
