package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.knowledge.timeline.VideoAsrSegment;
import com.videomind.module.knowledge.timeline.VideoAsrSegmentMapper;
import com.videomind.module.knowledge.timeline.VideoOcrObservationMapper;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAnalysisArtifactServiceTest {
    private final VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
    private final AiSummaryResultMapper summaries = mock(AiSummaryResultMapper.class);
    private final VideoAsrSegmentMapper asrSegments = mock(VideoAsrSegmentMapper.class);
    private final VideoOcrObservationMapper ocr = mock(VideoOcrObservationMapper.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final VideoAnalysisArtifactService service = new VideoAnalysisArtifactService(
            transcriptions, summaries, asrSegments, ocr, videos);

    @Test
    void createsTranscriptSegmentsAndVersionAsOneIdempotentArtifact() {
        TaskRecord task = task();
        VideoFile video = video(2);
        AsrResult result = AsrResult.builder().language("zh-CN").text("第一句")
                .segments(List.of(new AsrSegmentResult(100, 900, "第一句", 0))).build();
        VideoTranscription persisted = new VideoTranscription();
        persisted.setId(10L);
        persisted.setTaskId(11L);
        when(transcriptions.selectOne(any())).thenReturn(null, persisted);

        int first = service.persistAsr(task, video, result);
        int retried = service.persistAsr(task, video, result);

        assertThat(first).isEqualTo(3);
        assertThat(retried).isEqualTo(3);
        assertThat(video.getTranscriptVersion()).isEqualTo(3);
        verify(transcriptions).insert(any(VideoTranscription.class));
        verify(transcriptions).updateById(persisted);
        verify(asrSegments, org.mockito.Mockito.times(2)).upsert(any(VideoAsrSegment.class));
        verify(videos, org.mockito.Mockito.times(2)).updateById(video);
    }

    @Test
    void rebuildsAsrWithTimestampSegmentsFromDatabase() {
        VideoTranscription transcript = new VideoTranscription();
        transcript.setLanguage("zh-CN");
        transcript.setTranscriptionText("全文");
        VideoAsrSegment second = segment(1, 1000, 1800, "第二句");
        VideoAsrSegment first = segment(0, 100, 900, "第一句");
        when(transcriptions.selectOne(any())).thenReturn(transcript);
        when(asrSegments.selectList(any())).thenReturn(List.of(second, first));

        AsrResult rebuilt = service.loadAsr(task());

        assertThat(rebuilt.getText()).isEqualTo("全文");
        assertThat(rebuilt.getSegments()).containsExactly(
                new AsrSegmentResult(100, 900, "第一句", null),
                new AsrSegmentResult(1000, 1800, "第二句", null));
    }

    @Test
    void savesSummaryAndMatchingVideoVersionTogether() {
        TaskRecord task = task();
        VideoFile video = video(4);
        when(summaries.insert(any(AiSummaryResult.class))).thenAnswer(invocation -> {
            invocation.<AiSummaryResult>getArgument(0).setId(88L);
            return 1;
        });

        AiSummaryResult saved = service.saveSummary(task, video, 4,
                com.videomind.module.task.analysis.dto.SummaryResult.builder()
                        .summaryText("摘要").summaryJson("{}").modelName("model@v1").build());

        assertThat(saved.getId()).isEqualTo(88L);
        assertThat(video.getSummaryStatus()).isEqualTo("SUCCESS");
        assertThat(video.getSummaryVersion()).isEqualTo(4);
        assertThat(video.getLatestSummaryId()).isEqualTo("88");
        verify(videos).updateById(video);
    }

    private TaskRecord task() {
        TaskRecord value = new TaskRecord();
        value.setId(11L);
        value.setVideoId(5L);
        value.setUserId(7L);
        return value;
    }

    private VideoFile video(int version) {
        VideoFile value = new VideoFile();
        value.setId(5L);
        value.setTranscriptVersion(version);
        return value;
    }

    private VideoAsrSegment segment(int index, long start, long end, String text) {
        VideoAsrSegment value = new VideoAsrSegment();
        value.setTaskId(11L);
        value.setSegmentIndex(index);
        value.setStartMs(start);
        value.setEndMs(end);
        value.setText(text);
        value.setConfidence(BigDecimal.ONE);
        return value;
    }
}
