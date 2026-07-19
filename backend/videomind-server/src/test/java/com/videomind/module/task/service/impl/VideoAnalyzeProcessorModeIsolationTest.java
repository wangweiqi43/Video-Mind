package com.videomind.module.task.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.TaskStatus;
import com.videomind.module.agent.service.MindAgentVideoSyncService;
import com.videomind.module.knowledge.service.KnowledgeService;
import com.videomind.module.task.analysis.AudioExtractorClient;
import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.AiSummaryResult;
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

class VideoAnalyzeProcessorModeIsolationTest {

    private final TaskRecordService tasks = mock(TaskRecordService.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AudioExtractorClient audio = mock(AudioExtractorClient.class);
    private final SpeechToTextClient asr = mock(SpeechToTextClient.class);
    private final VideoSummaryClient summaries = mock(VideoSummaryClient.class);
    private final VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
    private final AiSummaryResultMapper summaryResults = mock(AiSummaryResultMapper.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final MindAgentVideoSyncService agentSync = mock(MindAgentVideoSyncService.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final VideoAnalyzeProcessorServiceImpl processor = new VideoAnalyzeProcessorServiceImpl(
            tasks, videos, audio, asr, summaries, transcriptions, summaryResults, knowledge, agentSync, redisson);

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
        task.setAutoVectorize(false);
        video = new VideoFile();
        video.setId(5L);
        video.setUserId(7L);
        video.setTranscriptVersion(1);
        VideoTranscription transcript = new VideoTranscription();
        transcript.setTaskId(3L);
        transcript.setVideoId(5L);
        transcript.setUserId(7L);
        transcript.setLanguage("zh");
        transcript.setTranscriptionText("第 3 页，共 10 分。");

        when(tasks.getTask(11L, 7L)).thenReturn(task);
        when(tasks.markProcessing(11L, 7L)).thenReturn(task);
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
        when(transcriptions.selectOne(any())).thenReturn(transcript);
        when(redisson.getLock(any(String.class))).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void advancedModeReusesAsrAndNeverRunsNormalSummaryOrLocalVectorization() {
        processor.process(message("ADVANCED"));

        verify(asr, never()).transcribe(any(), any(), any());
        verify(summaries, never()).summarize(any(), any(), any());
        verify(knowledge, never()).vectorizeTask(any(), any());
        verify(agentSync).sync(5L, 7L, 11L);
        verify(tasks, never()).markSuccess(11L, 7L);
    }

    @Test
    void normalModeReusesAsrAndNeverCallsMindAgent() {
        when(summaries.summarize(any(), eq(video), eq(task))).thenReturn(SummaryResult.builder()
                .summaryText("普通摘要")
                .summaryJson("{}")
                .modelName("model@v1")
                .build());

        processor.process(message("NORMAL"));

        verify(asr, never()).transcribe(any(), any(), any());
        verify(agentSync, never()).sync(any(), any(), any());
        verify(summaryResults).insert(any(AiSummaryResult.class));
        verify(tasks).markSuccess(11L, 7L);
    }

    @Test
    void normalModeMarksTaskSuccessfulBeforeAutomaticVectorization() {
        task.setAutoVectorize(true);
        when(summaries.summarize(any(), eq(video), eq(task))).thenReturn(SummaryResult.builder()
                .summaryText("普通摘要")
                .summaryJson("{}")
                .modelName("model@v1")
                .build());

        processor.process(message("NORMAL"));

        InOrder order = org.mockito.Mockito.inOrder(tasks, knowledge);
        order.verify(tasks).markSuccess(11L, 7L);
        order.verify(knowledge).vectorizeTask(11L, 7L);
    }

    private VideoAnalyzeMessage message(String mode) {
        return VideoAnalyzeMessage.builder()
                .taskId(11L)
                .videoId(5L)
                .userId(7L)
                .videoMd5("md5")
                .autoVectorize(false)
                .analysisMode(mode)
                .build();
    }
}
