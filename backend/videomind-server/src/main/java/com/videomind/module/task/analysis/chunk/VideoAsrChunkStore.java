package com.videomind.module.task.analysis.chunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.task.analysis.tencent.TencentAsrTaskResult;
import com.videomind.module.task.entity.TaskRecord;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VideoAsrChunkStore {
    private final VideoAsrChunkMapper mapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<VideoAsrChunk> ensurePlans(Long processingTaskId, TaskRecord task,
                                           List<AudioChunkArtifact> artifacts, String engineSignature) {
        LocalDateTime now = LocalDateTime.now();
        for (AudioChunkArtifact artifact : artifacts) {
            AudioChunkPlan plan = artifact.plan();
            VideoAsrChunk chunk = new VideoAsrChunk();
            chunk.setProcessingTaskId(processingTaskId);
            chunk.setTaskRecordId(task.getId());
            chunk.setVideoId(task.getVideoId());
            chunk.setUserId(task.getUserId());
            chunk.setChunkIndex(plan.chunkIndex());
            chunk.setExtractionStartMs(plan.extractionStartMs());
            chunk.setExtractionEndMs(plan.extractionEndMs());
            chunk.setLogicalStartMs(plan.logicalStartMs());
            chunk.setLogicalEndMs(plan.logicalEndMs());
            chunk.setEngineSignature(engineSignature);
            chunk.setState(VideoAsrChunkState.PLANNED);
            chunk.setSubmitAttempt(0);
            chunk.setCreatedTime(now);
            chunk.setUpdatedTime(now);
            mapper.upsertPlan(chunk);
            mapper.bindAudioChecksum(processingTaskId, plan.chunkIndex(), artifact.sha256(), now);
        }
        List<VideoAsrChunk> persisted = mapper.selectByProcessingTaskId(processingTaskId);
        verifyManifest(task, artifacts, engineSignature, persisted);
        return persisted;
    }

    public VideoAsrChunk refresh(Long id) {
        VideoAsrChunk chunk = mapper.selectById(id);
        if (chunk == null) throw new IllegalStateException("ASR_CHUNK_NOT_FOUND");
        return chunk;
    }

    public VideoAsrChunk claimSubmission(Long id) {
        mapper.claimSubmission(id, LocalDateTime.now());
        return refresh(id);
    }

    public VideoAsrChunk markSubmitted(Long id, int attempt, String providerTaskId) {
        mapper.markSubmitted(id, attempt, providerTaskId, LocalDateTime.now());
        VideoAsrChunk chunk = refresh(id);
        if (chunk.getState() != VideoAsrChunkState.SUBMITTED
                || !providerTaskId.equals(chunk.getProviderTaskId())) {
            throw new IllegalStateException("ASR_CHUNK_SUBMITTED_CAS_FAILED");
        }
        return chunk;
    }

    public VideoAsrChunk markSucceeded(VideoAsrChunk chunk, TencentAsrTaskResult result) {
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("ASR_CHUNK_RESULT_JSON_FAILED", failure);
        }
        mapper.markSucceeded(chunk.getId(), chunk.getProviderTaskId(), json, LocalDateTime.now());
        VideoAsrChunk persisted = refresh(chunk.getId());
        if (persisted.getState() != VideoAsrChunkState.SUCCEEDED) {
            throw new IllegalStateException("ASR_CHUNK_SUCCESS_CAS_FAILED");
        }
        return persisted;
    }

    public TencentAsrTaskResult loadResult(VideoAsrChunk chunk) {
        if (chunk.getState() != VideoAsrChunkState.SUCCEEDED || chunk.getResultJson() == null) {
            throw new IllegalStateException("ASR_CHUNK_RESULT_MISSING");
        }
        try {
            return objectMapper.readValue(chunk.getResultJson(), TencentAsrTaskResult.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("ASR_CHUNK_RESULT_CORRUPTED", failure);
        }
    }

    public VideoAsrChunk markFailed(Long id, String code, String message) {
        mapper.markFailed(id, code, truncate(message), LocalDateTime.now());
        return refresh(id);
    }

    public VideoAsrChunk recoverStaleSubmitting(VideoAsrChunk chunk, LocalDateTime cutoff) {
        mapper.recoverStaleSubmitting(chunk.getId(), cutoff, LocalDateTime.now());
        return refresh(chunk.getId());
    }

    public VideoAsrChunk expireSubmitted(VideoAsrChunk chunk, LocalDateTime cutoff) {
        mapper.expireSubmitted(chunk.getId(), cutoff, LocalDateTime.now());
        return refresh(chunk.getId());
    }

    private static void verifyManifest(TaskRecord task, List<AudioChunkArtifact> artifacts,
                                       String engineSignature, List<VideoAsrChunk> persisted) {
        if (persisted.size() != artifacts.size()) {
            throw new IllegalStateException("ASR_CHUNK_MANIFEST_SIZE_CHANGED");
        }
        for (int i = 0; i < artifacts.size(); i++) {
            AudioChunkArtifact artifact = artifacts.get(i);
            VideoAsrChunk chunk = persisted.get(i);
            AudioChunkPlan plan = artifact.plan();
            if (!processingIdentityMatches(task, chunk)
                    || chunk.getChunkIndex() != plan.chunkIndex()
                    || chunk.getExtractionStartMs() != plan.extractionStartMs()
                    || chunk.getExtractionEndMs() != plan.extractionEndMs()
                    || chunk.getLogicalStartMs() != plan.logicalStartMs()
                    || chunk.getLogicalEndMs() != plan.logicalEndMs()
                    || !artifact.sha256().equals(chunk.getAudioSha256())
                    || !engineSignature.equals(chunk.getEngineSignature())) {
                throw new IllegalStateException("ASR_CHUNK_MANIFEST_CHANGED");
            }
        }
    }

    private static boolean processingIdentityMatches(TaskRecord task, VideoAsrChunk chunk) {
        return task.getId().equals(chunk.getTaskRecordId())
                && task.getVideoId().equals(chunk.getVideoId())
                && task.getUserId().equals(chunk.getUserId());
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 2_048 ? value : value.substring(0, 2_048);
    }
}
