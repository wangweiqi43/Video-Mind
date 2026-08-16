package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VideoTimelineMaterializerTest {
    @Test
    void persistsTheExactPrefusedMarkdownAndJsonArtifacts() {
        VideoTimelineMapper timelines = mock(VideoTimelineMapper.class);
        ObjectStorageService storage = mock(ObjectStorageService.class);
        AtomicReference<VideoTimeline> saved = new AtomicReference<>();
        when(storage.putObject(anyString(), any(), anyLong(), anyString())).thenAnswer(call ->
                StoredObject.builder().bucket("knowledge").objectKey(call.getArgument(0)).build());
        when(timelines.insert(any(VideoTimeline.class))).thenAnswer(call -> {
            VideoTimeline value = call.getArgument(0);
            value.setId(55L);
            saved.set(value);
            return 1;
        });
        TimelineFusionService fusion = new TimelineFusionService();
        var timeline = fusion.fuse(List.of(new AsrSegment(0, 3_000, "事务消息", 0.9)),
                List.of(new OcrObservation(500, 500, "RocketMQ", 0.95)), 3_000, 30_000);
        String markdown = fusion.renderMarkdown(timeline, "课程视频 · 时间轴");
        FusedVideoContent content = new FusedVideoContent("课程视频", timeline, markdown, 1, 1, false);
        VideoTimelineMaterializer service = new VideoTimelineMaterializer(timelines, storage, new ObjectMapper());

        var result = service.materialize(9L, 12L, 7L, 3, content);

        assertThat(result.timelineId()).isEqualTo(55L);
        assertThat(result.markdown()).isEqualTo(markdown)
                .contains("课程视频 · 时间轴", "语音：事务消息", "画面文字：RocketMQ");
        assertThat(result.markdownObjectKey()).isEqualTo("knowledge/video/7/12/timeline/v3/timeline.md");
        assertThat(result.eventJsonObjectKey()).isEqualTo("knowledge/video/7/12/timeline/v3/events.json");
        assertThat(saved.get().getStatus()).isEqualTo("READY");
    }
}
