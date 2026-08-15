package com.videomind.module.task.analysis.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.tencent.TencentAsrTaskResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class AsrChunkResultMergerTest {
    private final AsrChunkResultMerger merger = new AsrChunkResultMerger();

    @Test
    void offsetsSortsAndAssignsOverlapToOneLogicalWindow() {
        CompletedAsrChunk second = completed(
                new AudioChunkPlan(1, 119_000, 241_000, 120_000, 240_000),
                new AsrSegmentResult(500, 1_500, "前片重复", 0),
                new AsrSegmentResult(1_200, 2_000, "第二句", 1));
        CompletedAsrChunk first = completed(
                new AudioChunkPlan(0, 0, 121_000, 0, 120_000),
                new AsrSegmentResult(119_500, 120_500, "前片重复", 0),
                new AsrSegmentResult(1_000, 2_000, "第一句", 0));

        var merged = merger.merge(List.of(second, first), 250_000);

        assertThat(merged.getText()).isEqualTo("第一句\n前片重复\n第二句");
        assertThat(merged.getSegments()).containsExactly(
                new AsrSegmentResult(1_000, 2_000, "第一句", 0),
                new AsrSegmentResult(119_500, 120_500, "前片重复", 0),
                new AsrSegmentResult(120_200, 121_000, "第二句", 1));
    }

    @Test
    void clampsTailAndDropsInvalidOrOutOfOwnershipSegments() {
        CompletedAsrChunk tail = completed(
                new AudioChunkPlan(2, 239_000, 250_000, 240_000, 250_000),
                new AsrSegmentResult(1_500, 2_500, "尾句", null),
                new AsrSegmentResult(20_000, 21_000, "越界", null),
                new AsrSegmentResult(-1, 20, "非法", null),
                new AsrSegmentResult(500, 600, "重叠归前片", null));

        var merged = merger.merge(List.of(tail), 250_000);

        assertThat(merged.getSegments()).containsExactly(
                new AsrSegmentResult(240_500, 241_500, "尾句", null));
    }

    @Test
    void rejectsEmptyTimestampEvidence() {
        CompletedAsrChunk empty = completed(
                new AudioChunkPlan(0, 0, 40_000, 0, 40_000));

        assertThatThrownBy(() -> merger.merge(List.of(empty), 40_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ASR_TIMESTAMP_SEGMENTS_EMPTY");
    }

    private static CompletedAsrChunk completed(AudioChunkPlan plan, AsrSegmentResult... segments) {
        return new CompletedAsrChunk(plan, new TencentAsrTaskResult(1,
                TencentAsrTaskResult.Status.SUCCEEDED, "", List.of(segments), "", "request"));
    }
}
