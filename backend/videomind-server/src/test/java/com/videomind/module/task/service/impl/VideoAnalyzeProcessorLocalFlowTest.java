package com.videomind.module.task.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.TaskStatus;
import com.videomind.module.knowledge.timeline.VideoTimelinePipeline;
import com.videomind.module.task.analysis.AudioExtractorClient;
import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.mq.VideoAnalyzeMessage;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class VideoAnalyzeProcessorLocalFlowTest {
    private final TaskRecordService tasks = mock(TaskRecordService.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AudioExtractorClient audio = mock(AudioExtractorClient.class);
    private final SpeechToTextClient asr = mock(SpeechToTextClient.class);
    private final VideoSummaryClient summaries = mock(VideoSummaryClient.class);
    private final VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
    private final AiSummaryResultMapper summaryResults = mock(AiSummaryResultMapper.class);
    private final VideoTimelinePipeline timeline = mock(VideoTimelinePipeline.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final VideoAnalyzeProcessorServiceImpl processor = new VideoAnalyzeProcessorServiceImpl(
            tasks, videos, audio, asr, summaries, transcriptions, summaryResults, timeline, redisson);
    private TaskRecord task;
    private VideoFile video;

    @BeforeEach
    void setUp() throws Exception {
        task = new TaskRecord();
        task.setId(11L);
        task.setVideoId(5L);
        task.setUserId(7L);
        task.setVideoMd5("md5");
        task.setTaskStatus(TaskStatus.PENDING);
        video = new VideoFile();
        video.setId(5L);
        video.setUserId(7L);
        video.setTranscriptVersion(1);
        VideoTranscription transcript = new VideoTranscription();
        transcript.setTaskId(3L);
        transcript.setVideoId(5L);
        transcript.setUserId(7L);
        transcript.setLanguage("zh");
        transcript.setTranscriptionText("已有转录");
        when(tasks.getTask(11L, 7L)).thenReturn(task);
        when(tasks.markProcessing(11L, 7L)).thenReturn(task);
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
        when(transcriptions.selectOne(any())).thenReturn(transcript);
        when(redisson.getLock(any(String.class))).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(summaries.summarize(any(), eq(video), eq(task))).thenReturn(SummaryResult.builder()
                .summaryText("本地摘要").summaryJson("{}").modelName("model@v1").build());
    }

    @Test
    void buildsTimelineAndSummaryBeforeMarkingLocalTaskSuccessful() {
        processor.process(message());

        verify(summaryResults).insert(any(AiSummaryResult.class));
        InOrder order = org.mockito.Mockito.inOrder(timeline, summaries, tasks);
        order.verify(timeline).build(eq(task), eq(video), eq(1), any());
        order.verify(summaries).summarize(any(), eq(video), eq(task));
        order.verify(tasks).markSuccess(11L, 7L);
    }

    private VideoAnalyzeMessage message() {
        return VideoAnalyzeMessage.builder().taskId(11L).videoId(5L).userId(7L).videoMd5("md5").build();
    }
}
