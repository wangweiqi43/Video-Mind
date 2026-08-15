package com.videomind.module.task.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.TimelineKnowledgeIndexer.IndexedTimeline;
import com.videomind.module.knowledge.timeline.VideoTimelinePipeline;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.service.ProcessingTaskHandler;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Executes video analysis from durable database artifacts at every stage boundary. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoAnalysisHandler implements ProcessingTaskHandler {
    static final String AUDIO_EXTRACTED = "AUDIO_EXTRACTED";
    static final String ASR_PERSISTED = "ASR_PERSISTED";
    static final String OCR_PERSISTED = "OCR_PERSISTED";
    static final String TIMELINE_INDEXED = "TIMELINE_INDEXED";
    static final String SUMMARY_SAVED = "SUMMARY_SAVED";
    static final String PUBLISHED = "PUBLISHED";

    private final ProcessingTaskMapper processingTasks;
    private final TaskRecordMapper taskRecords;
    private final VideoFileService videos;
    private final AudioExtractorClient audioExtractor;
    private final SpeechToTextClient speechToText;
    private final VideoSummaryClient summaryClient;
    private final VideoAnalysisArtifactService artifacts;
    private final VideoTimelinePipeline timelinePipeline;
    private final TaskCheckpointService checkpoints;
    private final TaskCancellationGuard cancellation;
    private final VideoAnalysisTempFileCleaner tempFiles;
    private final ObjectMapper objectMapper;

    @Override
    public ProcessingTaskType type() {
        return ProcessingTaskType.VIDEO_ANALYSIS;
    }

    @Override
    public String handle(TaskExecutionContext context) throws Exception {
        TaskData data = requireTaskData(context);
        try {
            return execute(context, data);
        } catch (TaskCancellationException cancelled) {
            tempFiles.cleanup(data.task());
            throw cancelled;
        }
    }

    private String execute(TaskExecutionContext context, TaskData data) throws Exception {
        cancellation.checkProcessingTask(context.taskId());
        AsrResult asr;
        if (checkpoints.isCompleted(context.taskId(), ASR_PERSISTED)) {
            asr = artifacts.loadAsr(data.task());
        } else {
            AudioExtractionResult audio = recoverOrExtractAudio(context.taskId(), data);
            cancellation.checkProcessingTask(context.taskId());
            asr = speechToText.transcribe(context.taskId(), audio, data.video(), data.task());
            cancellation.checkProcessingTask(context.taskId());
            if (asr.getSegments() == null || asr.getSegments().isEmpty()) {
                throw new IllegalStateException("ASR_TIMESTAMP_SEGMENTS_EMPTY");
            }
            int version = artifacts.persistAsr(data.task(), data.video(), asr);
            checkpoints.complete(context.taskId(), ASR_PERSISTED,
                    json(Map.of("taskRecordId", data.task().getId(), "transcriptVersion", version,
                            "segmentCount", asr.getSegments().size())), asrChecksum(asr));
        }

        int transcriptVersion = requireTranscriptVersion(data.video());
        List<OcrObservation> visuals;
        if (checkpoints.isCompleted(context.taskId(), OCR_PERSISTED)) {
            visuals = artifacts.loadOcr(data.task());
        } else {
            cancellation.checkProcessingTask(context.taskId());
            visuals = timelinePipeline.recognize(data.task(), data.video());
            cancellation.checkProcessingTask(context.taskId());
            artifacts.persistOcr(data.task(), visuals);
            checkpoints.complete(context.taskId(), OCR_PERSISTED,
                    json(Map.of("observationCount", visuals.size())), ocrChecksum(visuals));
        }

        List<AsrSegment> speech = artifacts.loadSpeech(data.task());
        if (!checkpoints.isCompleted(context.taskId(), TIMELINE_INDEXED)) {
            cancellation.checkProcessingTask(context.taskId());
            IndexedTimeline indexed = timelinePipeline.build(data.task(), data.video(), transcriptVersion,
                            speech, visuals)
                    .orElseThrow(() -> new IllegalStateException("VIDEO_TIMELINE_EMPTY"));
            cancellation.checkProcessingTask(context.taskId());
            checkpoints.complete(context.taskId(), TIMELINE_INDEXED,
                    json(Map.of("knowledgeBaseId", indexed.knowledgeBaseId(), "documentId", indexed.documentId(),
                            "versionId", indexed.versionId(), "chunkCount", indexed.chunkCount())),
                    sha256(asrChecksum(asr) + ":" + ocrChecksum(visuals) + ":" + transcriptVersion));
        }

        if (!checkpoints.isCompleted(context.taskId(), SUMMARY_SAVED)) {
            cancellation.checkProcessingTask(context.taskId());
            SummaryResult summary = summaryClient.summarize(asr, data.video(), data.task());
            cancellation.checkProcessingTask(context.taskId());
            AiSummaryResult saved = artifacts.saveSummary(data.task(), data.video(), transcriptVersion, summary);
            checkpoints.complete(context.taskId(), SUMMARY_SAVED,
                    json(Map.of("summaryId", saved.getId(), "model", safe(summary.getModelName()))),
                    sha256(safe(summary.getSummaryText()) + ":" + safe(summary.getSummaryJson())));
        }

        if (!checkpoints.isCompleted(context.taskId(), PUBLISHED)) {
            cancellation.checkProcessingTask(context.taskId());
            checkpoints.complete(context.taskId(), PUBLISHED,
                    json(Map.of("videoId", data.video().getId(), "transcriptVersion", transcriptVersion)),
                    sha256(data.video().getId() + ":" + transcriptVersion));
        }
        return PUBLISHED;
    }

    private TaskData requireTaskData(TaskExecutionContext context) {
        ProcessingTask processing = processingTasks.selectById(context.taskId());
        if (processing == null || processing.getTaskType() != ProcessingTaskType.VIDEO_ANALYSIS) {
            throw new IllegalStateException("VIDEO_PROCESSING_TASK_NOT_FOUND");
        }
        TaskRecord task = taskRecords.selectById(processing.getBusinessId());
        if (task == null || !processing.getUserId().equals(task.getUserId())) {
            throw new IllegalStateException("VIDEO_BUSINESS_TASK_NOT_FOUND");
        }
        if (!context.command().businessId().equals(task.getVideoId())) {
            throw new IllegalStateException("VIDEO_TASK_PAYLOAD_MISMATCH");
        }
        VideoFile video = videos.getVideoDetail(task.getVideoId(), task.getUserId());
        return new TaskData(task, video);
    }

    private AudioExtractionResult recoverOrExtractAudio(Long processingTaskId, TaskData data) throws Exception {
        TaskCheckpoint completed = completed(processingTaskId, AUDIO_EXTRACTED);
        if (completed != null) {
            AudioArtifact artifact = objectMapper.readValue(completed.getArtifactJson(), AudioArtifact.class);
            if (isVirtualAudio(artifact.audioPath())
                    && completed.getChecksum().equals(sha256(artifact.audioPath()))) {
                return AudioExtractionResult.builder().audioPath(artifact.audioPath())
                        .durationSeconds(artifact.durationSeconds()).build();
            }
            Path path = Path.of(artifact.audioPath());
            if (Files.isRegularFile(path) && completed.getChecksum().equals(sha256(Files.readAllBytes(path)))) {
                return AudioExtractionResult.builder().audioPath(path.toString())
                        .durationSeconds(artifact.durationSeconds()).build();
            }
            log.warn("Audio checkpoint artifact missing or corrupted; re-extract, processingTaskId={}",
                    processingTaskId);
        }
        AudioExtractionResult extracted = audioExtractor.extract(data.video(), data.task());
        String audioPath = extracted.getAudioPath();
        Path path = isVirtualAudio(audioPath) ? null : Path.of(audioPath);
        if (path != null && (!Files.isRegularFile(path) || Files.size(path) == 0)) {
            throw new IllegalStateException("AUDIO_ARTIFACT_INVALID");
        }
        String checksum = path == null ? sha256(audioPath) : sha256(Files.readAllBytes(path));
        if (completed != null && !checksum.equals(completed.getChecksum())) {
            throw new IllegalStateException("AUDIO_REEXTRACT_CHECKSUM_CHANGED");
        }
        if (extracted.getDurationSeconds() != null && extracted.getDurationSeconds() > 0) {
            data.video().setDurationSeconds(extracted.getDurationSeconds());
            videos.updateById(data.video());
        }
        checkpoints.complete(processingTaskId, AUDIO_EXTRACTED,
                json(new AudioArtifact(audioPath, extracted.getDurationSeconds())), checksum);
        return extracted;
    }

    private static int requireTranscriptVersion(VideoFile video) {
        if (video.getTranscriptVersion() == null || video.getTranscriptVersion() < 1) {
            throw new IllegalStateException("TRANSCRIPT_VERSION_MISSING");
        }
        return video.getTranscriptVersion();
    }

    private TaskCheckpoint completed(Long taskId, String stage) {
        return checkpoints.completed(taskId).stream().filter(value -> stage.equals(value.getStage())).findFirst()
                .orElse(null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("VIDEO_CHECKPOINT_JSON_FAILED", failure);
        }
    }

    private static String asrChecksum(AsrResult value) {
        StringBuilder joined = new StringBuilder(safe(value.getLanguage())).append(':').append(safe(value.getText()));
        for (AsrSegmentResult segment : value.getSegments()) {
            joined.append(':').append(segment.startMs()).append(':').append(segment.endMs()).append(':')
                    .append(safe(segment.text())).append(':').append(segment.speakerId());
        }
        return sha256(joined.toString());
    }

    private static String ocrChecksum(List<OcrObservation> values) {
        StringBuilder joined = new StringBuilder();
        for (OcrObservation value : values) {
            joined.append(':').append(value.startMs()).append(':').append(value.endMs()).append(':')
                    .append(safe(value.text())).append(':').append(value.confidence());
        }
        return sha256(joined.toString());
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isVirtualAudio(String value) {
        return value != null && value.startsWith("mock://");
    }

    private record AudioArtifact(String audioPath, Integer durationSeconds) {
    }

    private record TaskData(TaskRecord task, VideoFile video) {
    }
}
