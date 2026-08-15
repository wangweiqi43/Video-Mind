package com.videomind.module.task.analysis.chunk;

import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AsrChunkResultMerger {

    public AsrResult merge(List<CompletedAsrChunk> chunks, long videoDurationMs) {
        if (chunks == null || chunks.isEmpty() || videoDurationMs <= 0) {
            throw new IllegalArgumentException("ASR_CHUNK_RESULTS_REQUIRED");
        }
        List<OwnedSegment> owned = new ArrayList<>();
        for (int chunkOrder = 0; chunkOrder < chunks.size(); chunkOrder++) {
            CompletedAsrChunk completed = chunks.get(chunkOrder);
            AudioChunkPlan plan = completed.plan();
            List<AsrSegmentResult> segments = completed.result().segments();
            if (segments == null) continue;
            for (int segmentOrder = 0; segmentOrder < segments.size(); segmentOrder++) {
                AsrSegmentResult segment = segments.get(segmentOrder);
                if (segment == null || !StringUtils.hasText(segment.text())
                        || segment.startMs() < 0 || segment.endMs() < segment.startMs()) {
                    continue;
                }
                long startMs = clamp(plan.extractionStartMs() + segment.startMs(), 0, videoDurationMs);
                long endMs = clamp(plan.extractionStartMs() + segment.endMs(), 0, videoDurationMs);
                if (endMs <= startMs) continue;
                long midpoint = startMs + (endMs - startMs) / 2;
                if (midpoint < plan.logicalStartMs() || midpoint >= plan.logicalEndMs()) continue;
                owned.add(new OwnedSegment(startMs, endMs, segment.text().trim(), segment.speakerId(),
                        chunkOrder, segmentOrder));
            }
        }
        owned.sort(Comparator.comparingLong(OwnedSegment::startMs)
                .thenComparingLong(OwnedSegment::endMs)
                .thenComparingInt(OwnedSegment::chunkOrder)
                .thenComparingInt(OwnedSegment::segmentOrder));
        List<AsrSegmentResult> merged = owned.stream()
                .map(value -> new AsrSegmentResult(value.startMs(), value.endMs(),
                        value.text(), value.speakerId()))
                .toList();
        if (merged.isEmpty()) {
            throw new IllegalStateException("ASR_TIMESTAMP_SEGMENTS_EMPTY");
        }
        String text = merged.stream().map(AsrSegmentResult::text)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return AsrResult.builder().language("zh-CN").text(text).segments(merged).build();
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private record OwnedSegment(long startMs, long endMs, String text, Integer speakerId,
                                int chunkOrder, int segmentOrder) {
    }
}
