package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.timeline.FusedVideoContent;
import com.videomind.module.knowledge.timeline.TimelineFusionService;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.TimelineKnowledgeIndexer;
import com.videomind.module.knowledge.timeline.VideoTimelinePipeline;
import com.videomind.module.task.analysis.ParallelVideoAnalysisStage.BranchResults;
import com.videomind.module.task.analysis.ParallelVideoAnalysisStage.OcrBranchResult;
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
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
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
import org.mockito.InOrder;

class VideoAnalysisHandlerTest {
    private final ProcessingTaskMapper processingTasks = mock(ProcessingTaskMapper.class);
    private final TaskRecordMapper taskRecords = mock(TaskRecordMapper.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AudioExtractorClient audio = mock(AudioExtractorClient.class);
    private final ParallelVideoAnalysisStage parallel = mock(ParallelVideoAnalysisStage.class);
    private final VideoSummaryClient summary = mock(VideoSummaryClient.class);
    private final VideoAnalysisArtifactService artifacts = mock(VideoAnalysisArtifactService.class);
    private final VideoTimelinePipeline timeline = mock(VideoTimelinePipeline.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final TaskCancellationGuard cancellation = mock(TaskCancellationGuard.class);
    private final VideoAnalysisTempFileCleaner tempFiles = mock(VideoAnalysisTempFileCleaner.class);
    private final VideoAnalysisHandler handler = new VideoAnalysisHandler(processingTasks, taskRecords, videos,
            audio, parallel, summary, artifacts, timeline, checkpoints, cancellation, tempFiles,
            new ObjectMapper());
    private TaskRecord task;
    private VideoFile video;
    private AsrResult asr;
    private List<AsrSegment> speech;
    private List<OcrObservation> visuals;
    private FusedVideoContent fused;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
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
        video.setDurationSeconds(2);
        video.setTranscriptVersion(1);
        asr = AsrResult.builder().language("zh-CN").text("第一句")
                .segments(List.of(new AsrSegmentResult(100, 900, "第一句", 0))).build();
        speech = List.of(new AsrSegment(100, 900, "第一句", 1.0));
        visuals = List.of(new OcrObservation(200, 700, "架构图", 0.9));
        TimelineFusionService fusion = new TimelineFusionService();
        var fusedTimeline = fusion.fuse(speech, visuals, 2_000, 30_000);
        fused = new FusedVideoContent("demo.mp4", fusedTimeline,
                fusion.renderMarkdown(fusedTimeline, "demo.mp4 · 时间轴"), 1, 1, false);

        when(processingTasks.selectById(99L)).thenReturn(processing);
        when(taskRecords.selectById(11L)).thenReturn(task);
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);
        when(checkpoints.completed(99L)).thenReturn(List.of());
        when(parallel.execute(eq(99L), nullable(AudioExtractionResult.class), eq(task), eq(video)))
                .thenReturn(new BranchResults(asr, new OcrBranchResult(visuals, false, "NONE")));
        when(artifacts.loadSpeech(task)).thenReturn(speech);
        when(timeline.fuse(video, speech, visuals, false)).thenReturn(fused);
        when(timeline.materializeAndIndex(task, video, 1, fused))
                .thenReturn(Optional.of(new TimelineKnowledgeIndexer.IndexedTimeline(1L, 2L, 3L, 1)));
        when(summary.summarize(fused, video, task)).thenReturn(SummaryResult.builder()
                .summaryText("摘要").summaryJson("{}").modelName("model@v1").build());
        AiSummaryResult savedSummary = new AiSummaryResult();
        savedSummary.setId(55L);
        when(artifacts.saveSummary(eq(task), eq(video), any(Integer.class), any())).thenAnswer(invocation -> {
            int version = invocation.getArgument(2);
            video.setSummaryVersion(version);
            video.setLatestSummaryId("55");
            return savedSummary;
        });
    }

    @Test
    void extractsAudioBeforeParallelBranchesAndUsesOneFusionDownstream() throws Exception {
        Path audioFile = Files.writeString(tempDir.resolve("audio.wav"), "audio-content");
        AudioExtractionResult extracted = AudioExtractionResult.builder()
                .audioPath(audioFile.toString()).durationSeconds(2).build();
        when(audio.extract(video, task)).thenReturn(extracted);

        assertThat(handler.handle(context())).isEqualTo(VideoAnalysisHandler.PUBLISHED);

        InOrder flow = inOrder(audio, parallel, timeline, summary);
        flow.verify(audio).extract(video, task);
        flow.verify(parallel).execute(99L, extracted, task, video);
        flow.verify(timeline).fuse(video, speech, visuals, false);
        flow.verify(timeline).materializeAndIndex(task, video, 1, fused);
        flow.verify(summary).summarize(fused, video, task);
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.AUDIO_EXTRACTED), any(), any());
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.TIMELINE_INDEXED), any(), any());
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.SUMMARY_SAVED), any(), any());
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.PUBLISHED), any(), any());
        assertThat(video.getSummaryVersion()).isEqualTo(1);
    }

    @Test
    void skipsAudioAndIndexWhenDurableBranchesAndTimelineAlreadyExist() throws Exception {
        video.setTranscriptVersion(3);
        when(checkpoints.isCompleted(eq(99L), any())).thenAnswer(invocation -> {
            String stage = invocation.getArgument(1);
            return VideoAnalysisHandler.ASR_PERSISTED.equals(stage)
                    || VideoAnalysisHandler.OCR_PERSISTED.equals(stage)
                    || VideoAnalysisHandler.TIMELINE_INDEXED.equals(stage);
        });
        when(timeline.materializeAndIndex(any(), any(), any(Integer.class), any())).thenReturn(Optional.empty());

        handler.handle(context());

        verify(audio, never()).extract(any(), any());
        verify(parallel).execute(99L, null, task, video);
        verify(timeline, never()).materializeAndIndex(any(), any(), any(Integer.class), any());
        verify(summary).summarize(fused, video, task);
    }

    @Test
    void doesNotRunFusionOrSummaryWhenRequiredAsrBranchFails() throws Exception {
        Path audioFile = Files.writeString(tempDir.resolve("failed-audio.wav"), "audio-content");
        when(audio.extract(video, task)).thenReturn(AudioExtractionResult.builder()
                .audioPath(audioFile.toString()).durationSeconds(1).build());
        when(parallel.execute(eq(99L), any(), eq(task), eq(video)))
                .thenThrow(new IllegalStateException("ASR_FAILED"));

        assertThatThrownBy(() -> handler.handle(context()))
                .isInstanceOf(IllegalStateException.class).hasMessage("ASR_FAILED");

        verify(timeline, never()).fuse(any(), any(), any(), any(Boolean.class));
        verify(summary, never()).summarize(any(), any(), any());
        verify(tempFiles).cleanup(task);
    }

    @Test
    void cleansWorkspaceAfterParallelStageReportsCancellation() throws Exception {
        Path audioFile = Files.writeString(tempDir.resolve("cancel-audio.wav"), "audio-content");
        when(audio.extract(video, task)).thenReturn(AudioExtractionResult.builder()
                .audioPath(audioFile.toString()).durationSeconds(1).build());
        when(parallel.execute(eq(99L), any(), eq(task), eq(video))).thenThrow(new TaskCancellationException());

        assertThatThrownBy(() -> handler.handle(context())).isInstanceOf(TaskCancellationException.class);

        verify(tempFiles).cleanup(task);
        verify(timeline, never()).fuse(any(), any(), any(), any(Boolean.class));
    }

    private TaskExecutionContext context() {
        return new TaskExecutionContext(99L, "evt-1", new TaskCreateCommand(7L,
                ProcessingTaskType.VIDEO_ANALYSIS, 5L, "fingerprint", "PENDING", 5,
                Map.of("videoMd5", "md5")));
    }
}
