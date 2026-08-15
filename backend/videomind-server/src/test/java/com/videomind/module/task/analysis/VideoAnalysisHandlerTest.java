package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.TimelineKnowledgeIndexer;
import com.videomind.module.knowledge.timeline.VideoTimelinePipeline;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.service.ProcessingTaskHandler.TaskExecutionContext;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class VideoAnalysisHandlerTest {
    private final ProcessingTaskMapper processingTasks = mock(ProcessingTaskMapper.class);
    private final TaskRecordMapper taskRecords = mock(TaskRecordMapper.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AudioExtractorClient audio = mock(AudioExtractorClient.class);
    private final SpeechToTextClient speech = mock(SpeechToTextClient.class);
    private final VideoSummaryClient summary = mock(VideoSummaryClient.class);
    private final VideoAnalysisArtifactService artifacts = mock(VideoAnalysisArtifactService.class);
    private final VideoTimelinePipeline timeline = mock(VideoTimelinePipeline.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final TaskCancellationGuard cancellation = mock(TaskCancellationGuard.class);
    private final VideoAnalysisTempFileCleaner tempFiles = mock(VideoAnalysisTempFileCleaner.class);
    private final VideoAnalysisHandler handler = new VideoAnalysisHandler(processingTasks, taskRecords, videos,
            audio, speech, summary, artifacts, timeline, checkpoints, cancellation, tempFiles, new ObjectMapper());
    private TaskRecord task;
    private VideoFile video;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ProcessingTask processing = new ProcessingTask();
        processing.setId(99L);
        processing.setUserId(7L);
        processing.setTaskType(ProcessingTaskType.VIDEO_ANALYSIS);
        processing.setBusinessId(11L);
        task = new TaskRecord();
        task.setId(11L);
        task.setUserId(7L);
        task.setVideoId(5L);
        video = new VideoFile();
        video.setId(5L);
        video.setUserId(7L);
        video.setOriginalFilename("demo.mp4");
        when(processingTasks.selectById(99L)).thenReturn(processing);
        when(taskRecords.selectById(11L)).thenReturn(task);
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
        when(checkpoints.completed(99L)).thenReturn(List.of());
        AiSummaryResult savedSummary = new AiSummaryResult();
        savedSummary.setId(55L);
        when(artifacts.saveSummary(eq(task), eq(video), any(Integer.class), any())).thenAnswer(invocation -> {
            int version = invocation.getArgument(2);
            video.setSummaryVersion(version);
            video.setLatestSummaryId("55");
            return savedSummary;
        });
        when(timeline.build(eq(task), eq(video), any(Integer.class), anyList(), anyList()))
                .thenReturn(Optional.of(new TimelineKnowledgeIndexer.IndexedTimeline(1L, 2L, 3L, 1)));
        when(summary.summarize(any(), eq(video), eq(task))).thenReturn(SummaryResult.builder()
                .summaryText("摘要").summaryJson("{}").modelName("model@v1").build());
    }

    @Test
    void persistsEveryArtifactBeforeCompletingItsCheckpoint() throws Exception {
        Path audioFile = Files.writeString(tempDir.resolve("audio.wav"), "audio-content");
        AsrResult result = AsrResult.builder().language("zh-CN").text("第一句")
                .segments(List.of(new AsrSegmentResult(100, 900, "第一句", 0))).build();
        when(audio.extract(video, task)).thenReturn(AudioExtractionResult.builder()
                .audioPath(audioFile.toString()).durationSeconds(2).build());
        when(speech.transcribe(eq(99L), any(), eq(video), eq(task))).thenReturn(result);
        when(artifacts.persistAsr(task, video, result)).thenAnswer(invocation -> {
            video.setTranscriptVersion(1);
            return 1;
        });
        when(artifacts.loadSpeech(task)).thenReturn(List.of(
                new com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment(
                        100, 900, "第一句", 1.0)));
        when(timeline.recognize(task, video)).thenReturn(List.of(new OcrObservation(200, 700, "架构图", 0.9)));

        String finalStage = handler.handle(context());

        assertThat(finalStage).isEqualTo(VideoAnalysisHandler.PUBLISHED);
        verify(artifacts).persistAsr(task, video, result);
        verify(artifacts).persistOcr(eq(task), anyList());
        InOrder order = inOrder(checkpoints);
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.AUDIO_EXTRACTED), any(), any());
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.ASR_PERSISTED), any(), any());
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.OCR_PERSISTED), any(), any());
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.TIMELINE_INDEXED), any(), any());
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.SUMMARY_SAVED), any(), any());
        order.verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.PUBLISHED), any(), any());
        assertThat(video.getTranscriptVersion()).isEqualTo(1);
        assertThat(video.getSummaryVersion()).isEqualTo(1);
        assertThat(video.getLatestSummaryId()).isEqualTo("55");
    }

    @Test
    void rebuildsTimestampedAsrAndOcrFromDatabaseAfterCrash() throws Exception {
        video.setTranscriptVersion(3);
        AsrResult recovered = AsrResult.builder().language("zh-CN").text("持久化全文")
                .segments(List.of(new AsrSegmentResult(1000, 1800, "持久化分段", null))).build();
        var recoveredSpeech = List.of(
                new com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment(
                        1000, 1800, "持久化分段", 1.0));
        var recoveredOcr = List.of(new OcrObservation(1100, 1700, "持久化画面", 0.88));
        when(artifacts.loadAsr(task)).thenReturn(recovered);
        when(artifacts.loadSpeech(task)).thenReturn(recoveredSpeech);
        when(artifacts.loadOcr(task)).thenReturn(recoveredOcr);
        when(checkpoints.isCompleted(eq(99L), any())).thenAnswer(invocation -> {
            String stage = invocation.getArgument(1);
            return VideoAnalysisHandler.ASR_PERSISTED.equals(stage)
                    || VideoAnalysisHandler.OCR_PERSISTED.equals(stage);
        });

        handler.handle(context());

        verify(audio, never()).extract(any(), any());
        verify(speech, never()).transcribe(any(), any(), any(), any());
        verify(timeline, never()).recognize(any(), any());
        ArgumentCaptor<AsrResult> asr = ArgumentCaptor.forClass(AsrResult.class);
        verify(summary).summarize(asr.capture(), eq(video), eq(task));
        assertThat(asr.getValue().getSegments()).containsExactly(
                new AsrSegmentResult(1000, 1800, "持久化分段", null));
        verify(timeline).build(eq(task), eq(video), eq(3),
                eq(recoveredSpeech), eq(recoveredOcr));
    }

    @Test
    void supportsDeterministicVirtualAudioInMockMode() throws Exception {
        AsrResult result = AsrResult.builder().language("zh-CN").text("Mock 分段")
                .segments(List.of(new AsrSegmentResult(0, 1_000, "Mock 分段", 0))).build();
        when(audio.extract(video, task)).thenReturn(AudioExtractionResult.builder()
                .audioPath("mock://audio/task-11.wav").durationSeconds(1).build());
        when(speech.transcribe(eq(99L), any(), eq(video), eq(task))).thenReturn(result);
        when(artifacts.persistAsr(task, video, result)).thenAnswer(invocation -> {
            video.setTranscriptVersion(1);
            return 1;
        });
        when(artifacts.loadSpeech(task)).thenReturn(List.of(
                new com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment(
                        0, 1_000, "Mock 分段", 1.0)));
        when(timeline.recognize(task, video)).thenReturn(List.of());

        assertThat(handler.handle(context())).isEqualTo(VideoAnalysisHandler.PUBLISHED);

        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.AUDIO_EXTRACTED),
                org.mockito.ArgumentMatchers.contains("mock://audio/task-11.wav"), any());
    }

    @Test
    void cancellationAfterCloudAsrReturnsPreventsDatabasePersistenceAndCleansWorkspace() throws Exception {
        Path audioFile = Files.writeString(tempDir.resolve("cancel-audio.wav"), "audio-content");
        AsrResult result = AsrResult.builder().language("zh-CN").text("不应落库")
                .segments(List.of(new AsrSegmentResult(0, 1_000, "不应落库", 0))).build();
        when(audio.extract(video, task)).thenReturn(AudioExtractionResult.builder()
                .audioPath(audioFile.toString()).durationSeconds(1).build());
        when(speech.transcribe(eq(99L), any(), eq(video), eq(task))).thenReturn(result);
        doNothing().doNothing().doThrow(new TaskCancellationException())
                .when(cancellation).checkProcessingTask(99L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(context()))
                .isInstanceOf(TaskCancellationException.class);

        verify(artifacts, never()).persistAsr(any(), any(), any());
        verify(tempFiles).cleanup(task);
    }

    private TaskExecutionContext context() {
        return new TaskExecutionContext(99L, "evt-1", new TaskCreateCommand(7L,
                ProcessingTaskType.VIDEO_ANALYSIS, 5L, "fingerprint", "PENDING", 5,
                Map.of("videoMd5", "md5")));
    }

}
