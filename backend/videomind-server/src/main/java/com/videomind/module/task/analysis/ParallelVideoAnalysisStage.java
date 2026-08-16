package com.videomind.module.task.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.VideoAnalysisExecutorConfig;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.analysis.ocr.VideoKeyframeOcrService;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Runs the durable ASR and OCR branches concurrently after media preparation. */
@Slf4j
@Component
public class ParallelVideoAnalysisStage {
    private final SpeechToTextClient speechToText;
    private final VideoKeyframeOcrService ocr;
    private final VideoAnalysisArtifactService artifacts;
    private final TaskCheckpointService checkpoints;
    private final TaskCancellationGuard cancellation;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ParallelVideoAnalysisStage(SpeechToTextClient speechToText, VideoKeyframeOcrService ocr,
                                      VideoAnalysisArtifactService artifacts, TaskCheckpointService checkpoints,
                                      TaskCancellationGuard cancellation, ObjectMapper objectMapper,
                                      @Qualifier(VideoAnalysisExecutorConfig.BRANCH_EXECUTOR) Executor executor) {
        this.speechToText = speechToText;
        this.ocr = ocr;
        this.artifacts = artifacts;
        this.checkpoints = checkpoints;
        this.cancellation = cancellation;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public BranchResults execute(Long processingTaskId, AudioExtractionResult audio,
                                 TaskRecord task, VideoFile video) throws Exception {
        TaskCheckpoint asrCheckpoint = completed(processingTaskId, VideoAnalysisHandler.ASR_PERSISTED);
        TaskCheckpoint ocrCheckpoint = completed(processingTaskId, VideoAnalysisHandler.OCR_PERSISTED);
        CompletableFuture<AsrResult> asrFuture = async(() -> runAsr(processingTaskId, audio, task, video,
                asrCheckpoint));
        CompletableFuture<OcrBranchResult> ocrFuture = async(() -> runOcr(processingTaskId, task, video,
                ocrCheckpoint));
        awaitBoth(asrFuture, ocrFuture);
        cancellation.checkProcessingTask(processingTaskId);
        return new BranchResults(asrFuture.join(), ocrFuture.join());
    }

    private AsrResult runAsr(Long processingTaskId, AudioExtractionResult audio, TaskRecord task, VideoFile video,
                             TaskCheckpoint checkpoint) throws Exception {
        if (checkpoint != null) {
            return artifacts.loadAsr(task);
        }
        if (audio == null) {
            throw new IllegalStateException("AUDIO_ARTIFACT_REQUIRED");
        }
        cancellation.checkProcessingTask(processingTaskId);
        AsrResult result = speechToText.transcribe(processingTaskId, audio, video, task);
        cancellation.checkProcessingTask(processingTaskId);
        if (result.getSegments() == null || result.getSegments().isEmpty()) {
            throw new IllegalStateException("ASR_TIMESTAMP_SEGMENTS_EMPTY");
        }
        int version = artifacts.persistAsr(task, video, result);
        checkpoints.complete(processingTaskId, VideoAnalysisHandler.ASR_PERSISTED,
                json(new AsrArtifact(task.getId(), version, result.getSegments().size())),
                VideoAnalysisChecksums.asr(result));
        return result;
    }

    private OcrBranchResult runOcr(Long processingTaskId, TaskRecord task, VideoFile video,
                                   TaskCheckpoint checkpoint) {
        if (checkpoint != null) {
            OcrArtifact recovered = readOcrArtifact(checkpoint);
            return new OcrBranchResult(artifacts.loadOcr(task), recovered.degraded(), recovered.reason());
        }
        cancellation.checkProcessingTask(processingTaskId);
        List<OcrObservation> observations;
        boolean degraded = false;
        String reason = "NONE";
        try {
            observations = ocr.recognize(video, task);
        } catch (TaskCancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            degraded = true;
            reason = "OCR_UNAVAILABLE";
            observations = List.of();
            log.warn("OCR unavailable; continue with ASR-only content, taskId={}, videoId={}",
                    task.getId(), video.getId(), failure);
        }
        cancellation.checkProcessingTask(processingTaskId);
        List<OcrObservation> durable = observations == null ? List.of() : List.copyOf(observations);
        artifacts.persistOcr(task, durable);
        checkpoints.complete(processingTaskId, VideoAnalysisHandler.OCR_PERSISTED,
                json(new OcrArtifact(durable.size(), degraded, reason)), VideoAnalysisChecksums.ocr(durable));
        return new OcrBranchResult(durable, degraded, reason);
    }

    private OcrArtifact readOcrArtifact(TaskCheckpoint checkpoint) {
        try {
            OcrArtifact value = objectMapper.readValue(checkpoint.getArtifactJson(), OcrArtifact.class);
            return new OcrArtifact(value.observationCount(), value.degraded(),
                    value.reason() == null ? "NONE" : value.reason());
        } catch (Exception legacyOrCorruptArtifact) {
            log.warn("OCR checkpoint metadata is unreadable; treat durable observations as non-degraded, taskId={}",
                    checkpoint.getTaskId());
            return new OcrArtifact(0, false, "NONE");
        }
    }

    private TaskCheckpoint completed(Long taskId, String stage) {
        return checkpoints.completed(taskId).stream()
                .filter(value -> stage.equals(value.getStage()))
                .findFirst().orElse(null);
    }

    private <T> CompletableFuture<T> async(ThrowingSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (Throwable failure) {
                throw new CompletionException(failure);
            }
        }, executor);
    }

    private static void awaitBoth(CompletableFuture<?> left, CompletableFuture<?> right) throws Exception {
        try {
            CompletableFuture.allOf(left, right).join();
        } catch (CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("VIDEO_CHECKPOINT_JSON_FAILED", failure);
        }
    }

    public record BranchResults(AsrResult asr, OcrBranchResult ocr) {
    }

    public record OcrBranchResult(List<OcrObservation> observations, boolean degraded, String reason) {
        public OcrBranchResult {
            observations = observations == null ? List.of() : List.copyOf(observations);
            reason = reason == null ? "NONE" : reason;
        }
    }

    private record AsrArtifact(Long taskRecordId, int transcriptVersion, int segmentCount) {
    }

    private record OcrArtifact(int observationCount, boolean degraded, String reason) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
