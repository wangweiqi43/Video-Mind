package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.analysis.ocr.VideoKeyframeOcrService;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ParallelVideoAnalysisStageTest {
    private final SpeechToTextClient speech = mock(SpeechToTextClient.class);
    private final VideoKeyframeOcrService ocr = mock(VideoKeyframeOcrService.class);
    private final VideoAnalysisArtifactService artifacts = mock(VideoAnalysisArtifactService.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final TaskCancellationGuard cancellation = mock(TaskCancellationGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ParallelVideoAnalysisStage stage = new ParallelVideoAnalysisStage(speech, ocr, artifacts,
            checkpoints, cancellation, objectMapper, executor);
    private final TaskRecord task = new TaskRecord();
    private final VideoFile video = new VideoFile();
    private final AudioExtractionResult audio = AudioExtractionResult.builder()
            .audioPath("mock://audio/task-11.wav").durationSeconds(1).build();
    private AsrResult asr;

    @BeforeEach
    void setUp() {
        task.setId(11L);
        task.setUserId(7L);
        task.setVideoId(5L);
        video.setId(5L);
        video.setUserId(7L);
        video.setOriginalFilename("demo.mp4");
        asr = AsrResult.builder().language("zh-CN").text("并发语音")
                .segments(List.of(new AsrSegmentResult(0, 1_000, "并发语音", 0))).build();
        when(checkpoints.completed(99L)).thenReturn(List.of());
        when(artifacts.persistAsr(task, video, asr)).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void startsAsrAndOcrConcurrentlyAndCheckpointsBothDurableResults() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        when(speech.transcribe(99L, audio, video, task)).thenAnswer(invocation -> {
            bothEntered.countDown();
            if (!bothEntered.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("OCR_DID_NOT_START_CONCURRENTLY");
            }
            return asr;
        });
        List<OcrObservation> visuals = List.of(new OcrObservation(100, 800, "并发画面", 0.9));
        when(ocr.recognize(video, task)).thenAnswer(invocation -> {
            bothEntered.countDown();
            if (!bothEntered.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("ASR_DID_NOT_START_CONCURRENTLY");
            }
            return visuals;
        });

        var result = stage.execute(99L, audio, task, video);

        assertThat(result.asr()).isSameAs(asr);
        assertThat(result.ocr().observations()).containsExactlyElementsOf(visuals);
        assertThat(result.ocr().degraded()).isFalse();
        verify(artifacts).persistAsr(task, video, asr);
        verify(artifacts).persistOcr(task, visuals);
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.ASR_PERSISTED), any(), any());
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.OCR_PERSISTED), any(), any());
    }

    @Test
    void degradesOcrFailureToDurableAsrOnlyResult() throws Exception {
        when(speech.transcribe(99L, audio, video, task)).thenReturn(asr);
        when(ocr.recognize(video, task)).thenThrow(new IllegalStateException("local OCR offline"));

        var result = stage.execute(99L, audio, task, video);

        assertThat(result.ocr().observations()).isEmpty();
        assertThat(result.ocr().degraded()).isTrue();
        assertThat(result.ocr().reason()).isEqualTo("OCR_UNAVAILABLE");
        verify(artifacts).persistOcr(task, List.of());
        ArgumentCaptor<String> artifactJson = ArgumentCaptor.forClass(String.class);
        verify(checkpoints).complete(eq(99L), eq(VideoAnalysisHandler.OCR_PERSISTED), artifactJson.capture(), any());
        assertThat(artifactJson.getValue()).contains("\"observationCount\":0", "\"degraded\":true",
                "\"reason\":\"OCR_UNAVAILABLE\"");
    }

    @Test
    void restoresBothBranchesFromCheckpointsWithoutCallingProviders() throws Exception {
        TaskCheckpoint asrCheckpoint = checkpoint(VideoAnalysisHandler.ASR_PERSISTED, "{}");
        TaskCheckpoint ocrCheckpoint = checkpoint(VideoAnalysisHandler.OCR_PERSISTED,
                "{\"observationCount\":0,\"degraded\":true,\"reason\":\"OCR_UNAVAILABLE\"}");
        when(checkpoints.completed(99L)).thenReturn(List.of(asrCheckpoint, ocrCheckpoint));
        when(artifacts.loadAsr(task)).thenReturn(asr);
        when(artifacts.loadOcr(task)).thenReturn(List.of());

        var result = stage.execute(99L, null, task, video);

        assertThat(result.asr()).isSameAs(asr);
        assertThat(result.ocr().degraded()).isTrue();
        verify(speech, never()).transcribe(any(), any(), any(), any());
        verify(ocr, never()).recognize(any(), any());
    }

    @Test
    void waitsForOcrToStopBeforePropagatingAsrFailure() throws Exception {
        CountDownLatch ocrEntered = new CountDownLatch(1);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        AtomicBoolean ocrStopped = new AtomicBoolean();
        when(speech.transcribe(99L, audio, video, task)).thenThrow(new IllegalStateException("ASR_FAILED"));
        when(ocr.recognize(video, task)).thenAnswer(invocation -> {
            ocrEntered.countDown();
            if (!releaseOcr.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST_OCR_RELEASE_TIMEOUT");
            }
            ocrStopped.set(true);
            return List.of();
        });

        CompletableFuture<Void> execution = CompletableFuture.runAsync(() -> {
            try {
                stage.execute(99L, audio, task, video);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        assertThat(ocrEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(execution).isNotCompleted();
        releaseOcr.countDown();

        assertThatThrownBy(execution::join).hasRootCauseMessage("ASR_FAILED");
        assertThat(ocrStopped.get()).isTrue();
    }

    private TaskCheckpoint checkpoint(String stageName, String artifactJson) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskId(99L);
        checkpoint.setStage(stageName);
        checkpoint.setStatus("COMPLETED");
        checkpoint.setArtifactJson(artifactJson);
        return checkpoint;
    }
}
