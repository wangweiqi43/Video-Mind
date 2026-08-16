package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.config.AiProperties;
import com.videomind.config.RateLimitProperties;
import com.videomind.config.TencentAsrProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.module.task.dto.AnalyzeTaskCreateRequest;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.module.task.analysis.VideoKnowledgeReusePolicy;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskRecordServiceImplTest {
    private final VideoFileService videos = mock(VideoFileService.class);
    private final TransactionalTaskMessageProducer messages = mock(TransactionalTaskMessageProducer.class);
    private final RateLimitService rateLimit = mock(RateLimitService.class);
    private final RateLimitProperties rateLimitProperties = new RateLimitProperties();
    private final AiSummaryResultMapper summaries = mock(AiSummaryResultMapper.class);
    private final AiProperties ai = new AiProperties();
    private final TencentAsrProperties tencent = new TencentAsrProperties();
    private final ProcessingTaskMapper processingTasks = mock(ProcessingTaskMapper.class);
    private final VideoKnowledgeReusePolicy knowledgeReuse = mock(VideoKnowledgeReusePolicy.class);
    private TaskRecordServiceImpl service;
    private VideoFile video;

    @BeforeEach
    void setUp() {
        service = spy(new TaskRecordServiceImpl(videos, messages, rateLimit,
                rateLimitProperties, summaries, ai, tencent, processingTasks, knowledgeReuse));
        video = new VideoFile();
        video.setId(5L);
        video.setUserId(7L);
        video.setFileMd5("video-md5");
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
    }

    @Test
    void dispatchesOnlyTransactionalProcessingTaskAndReturnsBusinessTaskId() {
        TaskRecord created = task(11L, TaskStatus.PENDING);
        doReturn(null, created).when(service).getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-1", 99L, 11L, false));

        var response = service.createAnalyzeTask(request(), 7L);

        assertThat(response.getTaskId()).isEqualTo(11L);
        assertThat(response.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(response.getReused()).isFalse();
        ArgumentCaptor<TaskCreateCommand> command = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(messages).dispatch(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo(ProcessingTaskType.VIDEO_ANALYSIS);
        assertThat(command.getValue().businessId()).isEqualTo(5L);
        assertThat(command.getValue().businessFingerprint())
                .startsWith("VIDEO_ANALYSIS:7:5:").hasSize(83);
        assertThat(command.getValue().payload()).containsEntry("videoMd5", "video-md5")
                .containsEntry("timelineVersion", "timeline-fusion-v3-layered-ocr-anchor");
    }

    @Test
    void returnsTransactionWinnerWhenConcurrentRequestReusesRunningTask() {
        TaskRecord running = task(12L, TaskStatus.PROCESSING);
        doReturn(null, running).when(service).getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-2", 100L, 12L, true));

        var response = service.createAnalyzeTask(request(), 7L);

        assertThat(response.getTaskId()).isEqualTo(12L);
        assertThat(response.getReused()).isTrue();
    }

    @Test
    void reusesCompatibleCompletedResultWithoutSendingMessage() {
        video.setSummaryStatus("SUCCESS");
        video.setTranscriptVersion(3);
        video.setSummaryVersion(3);
        TaskRecord completed = task(10L, TaskStatus.SUCCESS);
        AiSummaryResult summary = new AiSummaryResult();
        summary.setModelName("mock-summary@v1");
        ai.getSummary().setMode("mock");
        ai.getSummary().setPromptVersion("v1");
        doReturn(completed).when(service).getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(summaries.selectOne(any())).thenReturn(summary);
        ProcessingTask processing = new ProcessingTask();
        processing.setState(ProcessingTaskState.SUCCESS);
        processing.setBusinessFingerprint(service.videoFingerprint(7L, video));
        when(processingTasks.selectOne(any())).thenReturn(processing);
        when(knowledgeReuse.isReusable(7L, 5L, 10L, 3)).thenReturn(true);

        var response = service.createAnalyzeTask(request(), 7L);

        assertThat(response.getTaskId()).isEqualTo(10L);
        assertThat(response.getReused()).isTrue();
        verify(messages, never()).dispatch(any());
    }

    @Test
    void rebuildsCompletedResultWhenKnowledgeAssetsAreStale() {
        video.setSummaryStatus("SUCCESS");
        video.setTranscriptVersion(3);
        video.setSummaryVersion(3);
        TaskRecord completed = task(10L, TaskStatus.SUCCESS);
        TaskRecord rebuilt = task(13L, TaskStatus.PENDING);
        AiSummaryResult summary = new AiSummaryResult();
        summary.setModelName("mock-summary@v1");
        ai.getSummary().setMode("mock");
        ai.getSummary().setPromptVersion("v1");
        ProcessingTask processing = new ProcessingTask();
        processing.setState(ProcessingTaskState.SUCCESS);
        processing.setBusinessFingerprint(service.videoFingerprint(7L, video));
        doReturn(completed, rebuilt).when(service)
                .getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(summaries.selectOne(any())).thenReturn(summary);
        when(processingTasks.selectOne(any())).thenReturn(processing);
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-rebuild", 101L, 13L, false));

        var response = service.createAnalyzeTask(request(), 7L);

        assertThat(response.getTaskId()).isEqualTo(13L);
        assertThat(response.getReused()).isFalse();
        verify(messages).dispatch(any());
    }

    @Test
    void rebuildsCompletedResultWhenItsFullProcessingFingerprintIsStale() {
        video.setSummaryStatus("SUCCESS");
        video.setTranscriptVersion(3);
        video.setSummaryVersion(3);
        TaskRecord completed = task(10L, TaskStatus.SUCCESS);
        TaskRecord rebuilt = task(14L, TaskStatus.PENDING);
        AiSummaryResult summary = new AiSummaryResult();
        summary.setModelName("mock-summary@v1");
        ai.getSummary().setMode("mock");
        ai.getSummary().setPromptVersion("v1");
        ProcessingTask processing = new ProcessingTask();
        processing.setState(ProcessingTaskState.SUCCESS);
        processing.setBusinessFingerprint("VIDEO_ANALYSIS:7:5:stale");
        doReturn(completed, rebuilt).when(service)
                .getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(summaries.selectOne(any())).thenReturn(summary);
        when(processingTasks.selectOne(any())).thenReturn(processing);
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-rebuild", 102L, 14L, false));

        var response = service.createAnalyzeTask(request(), 7L);

        assertThat(response.getTaskId()).isEqualTo(14L);
        verify(knowledgeReuse, never()).isReusable(any(), any(), any(), any());
        verify(messages).dispatch(any());
    }

    private AnalyzeTaskCreateRequest request() {
        AnalyzeTaskCreateRequest value = new AnalyzeTaskCreateRequest();
        value.setVideoId(5L);
        return value;
    }

    private TaskRecord task(Long id, TaskStatus status) {
        TaskRecord value = new TaskRecord();
        value.setId(id);
        value.setUserId(7L);
        value.setVideoId(5L);
        value.setVideoMd5("video-md5");
        value.setTaskStatus(status);
        return value;
    }

}
