package com.videomind.module.task.analysis.tencent;

import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import java.util.List;

public record TencentAsrTaskResult(
        long taskId,
        Status status,
        String text,
        List<AsrSegmentResult> segments,
        String errorMessage,
        String requestId
) {
    public enum Status {
        WAITING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
