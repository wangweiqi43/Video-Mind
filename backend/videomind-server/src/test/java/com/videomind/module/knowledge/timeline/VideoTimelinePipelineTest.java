package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.config.OcrProperties;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoTimelinePipelineTest {
    private final VideoTimelineMaterializer materializer = mock(VideoTimelineMaterializer.class);
    private final TimelineKnowledgeIndexer indexer = mock(TimelineKnowledgeIndexer.class);
    private final VideoTimelinePipeline pipeline = new VideoTimelinePipeline(new TimelineFusionService(),
            new OcrProperties(), materializer, indexer);

    @Test
    void producesOneCanonicalFusionForPersistenceIndexingAndSummary() {
        TaskRecord task = task();
        VideoFile video = video();
        FusedVideoContent content = pipeline.fuse(video,
                List.of(new AsrSegment(100, 900, "第一句", 1.0)),
                List.of(new OcrObservation(200, 700, "架构图", 0.9)), false);

        assertThat(content.markdown()).contains("语音：第一句", "画面文字：架构图");
        assertThat(content.timeline().speechBlocks()).hasSize(1);
        assertThat(content.timeline().visualSpans()).hasSize(1);
        assertThat(content.asrSegmentCount()).isEqualTo(1);
        assertThat(content.ocrObservationCount()).isEqualTo(1);
        assertThat(content.ocrDegraded()).isFalse();

        var materialized = new VideoTimelineMaterializer.MaterializedTimeline(1L, content.timeline(),
                content.markdown(), "bucket", "timeline.md", "events.json");
        var indexed = new TimelineKnowledgeIndexer.IndexedTimeline(1L, 2L, 3L, 1);
        when(materializer.materialize(9L, 5L, 7L, 2, content)).thenReturn(materialized);
        when(indexer.index(7L, 5L, "demo.mp4", 2, materialized)).thenReturn(indexed);

        assertThat(pipeline.materializeAndIndex(task, video, 2, content)).contains(indexed);
        verify(materializer).materialize(9L, 5L, 7L, 2, content);
    }

    @Test
    void skipsTimelineWhenNeitherBranchHasUsableEvents() {
        FusedVideoContent content = pipeline.fuse(video(), List.of(), List.of(), true);

        assertThat(pipeline.materializeAndIndex(task(), video(), 1, content)).isEmpty();
        verify(materializer, never()).materialize(any(), any(), any(), any(Integer.class), any());
        verify(indexer, never()).index(any(), any(), any(), any(Integer.class), any());
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
        video.setDurationSeconds(30);
        return video;
    }
}
