package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.ocr.VideoKeyframeOcrService;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoTimelinePipelineTest {
    private final VideoKeyframeOcrService ocr = mock(VideoKeyframeOcrService.class);
    private final VideoTimelineMaterializer materializer = mock(VideoTimelineMaterializer.class);
    private final TimelineKnowledgeIndexer indexer = mock(TimelineKnowledgeIndexer.class);
    private final VideoTimelinePipeline pipeline = new VideoTimelinePipeline(ocr, materializer, indexer);

    @Test
    void degradesToSpeechOnlyTimelineWhenLocalOcrFails() {
        TaskRecord task = task();
        VideoFile video = video();
        AsrResult asr = AsrResult.builder().text("第一句").segments(List.of(
                new AsrSegmentResult(100, 900, "第一句", 0))).build();
        var timeline = mock(VideoTimelineMaterializer.MaterializedTimeline.class);
        var indexed = new TimelineKnowledgeIndexer.IndexedTimeline(1L, 2L, 3L, 1);
        when(ocr.recognize(video, task)).thenThrow(new IllegalStateException("OCR offline"));
        when(materializer.materialize(eq(9L), eq(5L), eq(7L), eq(2), eq("demo.mp4"), any(), eq(List.of())))
                .thenReturn(timeline);
        when(indexer.index(7L, 5L, "demo.mp4", 2, timeline)).thenReturn(indexed);

        var result = pipeline.build(task, video, 2, asr);

        assertThat(result).contains(indexed);
        verify(materializer).materialize(eq(9L), eq(5L), eq(7L), eq(2), eq("demo.mp4"),
                eq(List.of(new TimelineFusionService.AsrSegment(100, 900, "第一句", 1.0))), eq(List.of()));
    }

    @Test
    void skipsLegacyTranscriptWithoutTimestamps() {
        var result = pipeline.build(task(), video(), 1, AsrResult.builder().text("旧转录").build());
        assertThat(result).isEmpty();
        verify(ocr, never()).recognize(any(), any());
        verify(materializer, never()).materialize(any(), any(), any(), any(Integer.class), any(), any(), any());
    }

    private TaskRecord task() {
        TaskRecord task = new TaskRecord();
        task.setId(9L);
        task.setVideoId(5L);
        task.setUserId(7L);
        return task;
    }

    private VideoFile video() {
        VideoFile video = new VideoFile();
        video.setId(5L);
        video.setOriginalFilename("demo.mp4");
        return video;
    }
}
