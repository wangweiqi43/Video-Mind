package com.videomind.module.task.analysis.tencent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.TencentAsrProperties;
import com.videomind.module.task.analysis.chunk.AudioChunkArtifact;
import com.videomind.module.task.analysis.chunk.CompletedAsrChunk;
import com.videomind.module.task.analysis.chunk.VideoAsrChunk;
import com.videomind.module.task.analysis.chunk.VideoAsrChunkState;
import com.videomind.module.task.analysis.chunk.VideoAsrChunkStore;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationGuard;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${videomind.ai.asr.mode:mock}' == 'real' && '${videomind.ai.asr.provider:generic}' == 'tencent'")
public class TencentAsrChunkTranscriber {
    private final TencentAsrProperties properties;
    private final TencentAsrApiTransport transport;
    private final TencentAsrResponseParser parser;
    private final VideoAsrChunkStore store;
    private final TaskCancellationGuard cancellation;
    private final ObjectMapper objectMapper;

    public List<CompletedAsrChunk> transcribe(Long processingTaskId, List<AudioChunkArtifact> artifacts,
                                               TaskRecord task) {
        validateConfiguration();
        String signature = engineSignature();
        List<VideoAsrChunk> chunks = store.ensurePlans(processingTaskId, task, artifacts, signature);
        List<CompletedAsrChunk> completed = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            cancellation.checkProcessingTask(processingTaskId);
            AudioChunkArtifact artifact = artifacts.get(i);
            VideoAsrChunk chunk = chunks.get(i);
            TencentAsrTaskResult result = resolve(processingTaskId, chunk, artifact);
            completed.add(new CompletedAsrChunk(artifact.plan(), result));
            deleteCompletedArtifact(artifact);
        }
        return List.copyOf(completed);
    }

    private TencentAsrTaskResult resolve(Long processingTaskId, VideoAsrChunk initial,
                                         AudioChunkArtifact artifact) {
        VideoAsrChunk chunk = initial;
        for (int transition = 0; transition < 6; transition++) {
            cancellation.checkProcessingTask(processingTaskId);
            switch (chunk.getState()) {
                case SUCCEEDED -> {
                    return store.loadResult(chunk);
                }
                case SUBMITTED -> {
                    LocalDateTime expiryCutoff = LocalDateTime.now()
                            .minusHours(Math.max(1, properties.getProviderTaskTtlHours()));
                    if (chunk.getSubmittedTime() == null || chunk.getSubmittedTime().isBefore(expiryCutoff)) {
                        chunk = store.expireSubmitted(chunk, expiryCutoff);
                        continue;
                    }
                    return poll(processingTaskId, chunk);
                }
                case SUBMITTING -> {
                    LocalDateTime staleCutoff = LocalDateTime.now().minusSeconds(
                            Math.max(1, properties.getSubmissionUnknownTimeoutSeconds()));
                    VideoAsrChunk recovered = store.recoverStaleSubmitting(chunk, staleCutoff);
                    if (recovered.getState() == VideoAsrChunkState.SUBMITTING) {
                        throw new BizException(503, "腾讯云 ASR 分片提交结果尚未确定，请稍后重试");
                    }
                    chunk = recovered;
                }
                case PLANNED, FAILED -> {
                    VideoAsrChunk claimed = store.claimSubmission(chunk.getId());
                    if (claimed.getState() != VideoAsrChunkState.SUBMITTING) {
                        chunk = claimed;
                        continue;
                    }
                    return submitAndPoll(processingTaskId, claimed, artifact);
                }
            }
        }
        throw new IllegalStateException("ASR_CHUNK_STATE_DID_NOT_CONVERGE");
    }

    private TencentAsrTaskResult submitAndPoll(Long processingTaskId, VideoAsrChunk chunk,
                                               AudioChunkArtifact artifact) {
        cancellation.checkProcessingTask(processingTaskId);
        String response = transport.post("CreateRecTask", createInlinePayload(artifact));
        long providerTaskId;
        try {
            providerTaskId = parser.parseCreatedTaskId(response);
        } catch (RuntimeException rejected) {
            store.markFailed(chunk.getId(), "CREATE_REJECTED", rejected.getMessage());
            throw rejected;
        }
        cancellation.checkProcessingTask(processingTaskId);
        VideoAsrChunk submitted = store.markSubmitted(chunk.getId(), chunk.getSubmitAttempt(),
                Long.toUnsignedString(providerTaskId));
        return poll(processingTaskId, submitted);
    }

    private TencentAsrTaskResult poll(Long processingTaskId, VideoAsrChunk chunk) {
        long deadline = System.nanoTime() + Duration.ofSeconds(properties.getTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            cancellation.checkProcessingTask(processingTaskId);
            TencentAsrTaskResult result = parser.parse(transport.post("DescribeTaskStatus", payload(Map.of(
                    "TaskId", Long.parseUnsignedLong(chunk.getProviderTaskId())))));
            cancellation.checkProcessingTask(processingTaskId);
            if (result.status() == TencentAsrTaskResult.Status.SUCCEEDED) {
                store.markSucceeded(chunk, result);
                return result;
            }
            if (result.status() == TencentAsrTaskResult.Status.FAILED) {
                store.markFailed(chunk.getId(), "PROVIDER_FAILED", result.errorMessage());
                throw new BizException(502, "腾讯云 ASR 识别失败：" + result.errorMessage());
            }
            waitBeforeNextPoll();
        }
        throw new BizException(504, "腾讯云 ASR 分片轮询超时，chunk=" + chunk.getChunkIndex());
    }

    private String createInlinePayload(AudioChunkArtifact artifact) {
        try {
            byte[] audio = Files.readAllBytes(artifact.path());
            if (audio.length != artifact.sizeBytes() || audio.length > properties.getMaxInlineAudioBytes()) {
                throw new BizException(500, "ASR 分片大小在提交前发生变化，chunk="
                        + artifact.plan().chunkIndex());
            }
            Map<String, Object> values = baseCreatePayload();
            values.put("SourceType", 1);
            values.put("Data", Base64.getEncoder().encodeToString(audio));
            values.put("DataLen", audio.length);
            return payload(values);
        } catch (BizException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BizException(500, "读取 ASR 分片失败：" + failure.getMessage());
        }
    }

    private Map<String, Object> baseCreatePayload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("EngineModelType", properties.getEngineModelType());
        values.put("ChannelNum", 1);
        values.put("ResTextFormat", 1);
        return values;
    }

    private String payload(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("构造腾讯云 ASR 请求失败", failure);
        }
    }

    private String engineSignature() {
        String value = String.join("|", properties.getEngineModelType(), "channel=1", "format=1",
                "chunk=" + properties.getChunkSeconds(),
                "overlap=" + properties.getChunkOverlapMillis());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void waitBeforeNextPoll() {
        try {
            Thread.sleep(Math.max(100, properties.getPollIntervalMillis()));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "腾讯云 ASR 轮询被中断");
        }
    }

    private void deleteCompletedArtifact(AudioChunkArtifact artifact) {
        try {
            Files.deleteIfExists(artifact.path());
        } catch (Exception failure) {
            log.warn("Failed to delete completed ASR chunk, path={}", artifact.path(), failure);
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getSecretId()) || !StringUtils.hasText(properties.getSecretKey())) {
            throw new BizException(500, "腾讯云 ASR SecretId/SecretKey 未配置");
        }
        if (!StringUtils.hasText(properties.getEndpoint()) || !StringUtils.hasText(properties.getRegion())) {
            throw new BizException(500, "腾讯云 ASR endpoint/region 未配置");
        }
    }
}
