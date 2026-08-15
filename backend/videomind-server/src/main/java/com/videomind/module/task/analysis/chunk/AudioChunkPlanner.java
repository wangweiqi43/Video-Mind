package com.videomind.module.task.analysis.chunk;

import com.videomind.config.TencentAsrProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AudioChunkPlanner {
    private final TencentAsrProperties properties;

    public List<AudioChunkPlan> plan(long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("AUDIO_DURATION_REQUIRED");
        }
        long logicalLengthMs = Math.multiplyExact(properties.getChunkSeconds(), 1_000L);
        long overlapMs = properties.getChunkOverlapMillis();
        if (logicalLengthMs <= 0 || overlapMs < 0 || overlapMs >= logicalLengthMs) {
            throw new IllegalArgumentException("INVALID_AUDIO_CHUNK_CONFIGURATION");
        }
        List<AudioChunkPlan> result = new ArrayList<>();
        int index = 0;
        for (long logicalStart = 0; logicalStart < durationMs; logicalStart += logicalLengthMs) {
            long logicalEnd = Math.min(durationMs, Math.addExact(logicalStart, logicalLengthMs));
            long extractionStart = Math.max(0, logicalStart - overlapMs);
            long extractionEnd = Math.min(durationMs, Math.addExact(logicalEnd, overlapMs));
            result.add(new AudioChunkPlan(index++, extractionStart, extractionEnd, logicalStart, logicalEnd));
        }
        return List.copyOf(result);
    }
}
