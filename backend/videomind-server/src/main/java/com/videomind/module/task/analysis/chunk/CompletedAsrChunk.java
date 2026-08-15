package com.videomind.module.task.analysis.chunk;

import com.videomind.module.task.analysis.tencent.TencentAsrTaskResult;

public record CompletedAsrChunk(
        AudioChunkPlan plan,
        TencentAsrTaskResult result
) {
}
