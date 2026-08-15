package com.videomind.module.task.analysis.chunk;

public record AudioChunkPlan(
        int chunkIndex,
        long extractionStartMs,
        long extractionEndMs,
        long logicalStartMs,
        long logicalEndMs
) {
    public AudioChunkPlan {
        if (chunkIndex < 0 || extractionStartMs < 0 || logicalStartMs < 0
                || extractionEndMs <= extractionStartMs || logicalEndMs <= logicalStartMs
                || extractionStartMs > logicalStartMs || extractionEndMs < logicalEndMs) {
            throw new IllegalArgumentException("INVALID_AUDIO_CHUNK_PLAN");
        }
    }

    public long extractionDurationMs() {
        return extractionEndMs - extractionStartMs;
    }
}
