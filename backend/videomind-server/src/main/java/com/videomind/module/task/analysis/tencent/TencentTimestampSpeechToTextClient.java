package com.videomind.module.task.analysis.tencent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.TencentAsrProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.video.entity.VideoFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnExpression("'${videomind.ai.asr.mode:mock}' == 'real' && '${videomind.ai.asr.provider:generic}' == 'tencent'")
public class TencentTimestampSpeechToTextClient implements SpeechToTextClient {
    private static final long MAX_INLINE_AUDIO_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TencentTimestampSpeechToTextClient.class);
    private final TencentAsrProperties properties;
    private final ObjectStorageService storage;
    private final TencentAsrApiTransport transport;
    private final TencentAsrResponseParser parser;
    private final ObjectMapper objectMapper;
    private final TaskCancellationGuard cancellation;

    public TencentTimestampSpeechToTextClient(TencentAsrProperties properties, ObjectStorageService storage,
                                               TencentAsrApiTransport transport,
                                               TencentAsrResponseParser parser, ObjectMapper objectMapper,
                                               TaskCancellationGuard cancellation) {
        this.properties = properties;
        this.storage = storage;
        this.transport = transport;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.cancellation = cancellation;
    }

    @Override
    public AsrResult transcribe(AudioExtractionResult audio, VideoFile videoFile, TaskRecord taskRecord) {
        validateConfiguration();
        cancellation.checkVideoTask(taskRecord.getId());
        Path audioPath = Path.of(audio.getAudioPath());
        if (!Files.isRegularFile(audioPath)) {
            throw new BizException(500, "ASR 音频文件不存在：" + audioPath);
        }
        StoredObject uploaded = null;
        try {
            String requestPayload;
            long audioSize = audioSize(audioPath);
            if (audioSize <= MAX_INLINE_AUDIO_BYTES) {
                requestPayload = createInlinePayload(audioPath);
            } else {
                uploaded = upload(audioPath, taskRecord.getId());
                int expiry = Math.max(properties.getPresignedUrlExpirySeconds(), properties.getTimeoutSeconds() + 300);
                String audioUrl = storage.presignGetUrl(uploaded.getBucket(), uploaded.getObjectKey(),
                        Duration.ofSeconds(expiry));
                requestPayload = createUrlPayload(audioUrl);
            }
            long cloudTaskId = parser.parseCreatedTaskId(transport.post("CreateRecTask", requestPayload));
            TencentAsrTaskResult result = poll(cloudTaskId, taskRecord.getId());
            return AsrResult.builder()
                    .language("zh-CN")
                    .text(result.text())
                    .segments(result.segments())
                    .build();
        } finally {
            if (uploaded != null) {
                try {
                    storage.removeObject(uploaded.getBucket(), uploaded.getObjectKey());
                } catch (RuntimeException exception) {
                    log.warn("清理腾讯云 ASR 临时音频失败，bucket={}, objectKey={}",
                            uploaded.getBucket(), uploaded.getObjectKey(), exception);
                }
            }
        }
    }

    private long audioSize(Path audioPath) {
        try {
            return Files.size(audioPath);
        } catch (IOException exception) {
            throw new BizException(500, "检查 ASR 音频大小失败：" + exception.getMessage());
        }
    }

    private String createInlinePayload(Path audioPath) {
        try {
            byte[] audioData = Files.readAllBytes(audioPath);
            Map<String, Object> values = baseCreatePayload();
            values.put("SourceType", 1);
            values.put("Data", Base64.getEncoder().encodeToString(audioData));
            values.put("DataLen", audioData.length);
            return payload(values);
        } catch (IOException exception) {
            throw new BizException(500, "读取 ASR 音频失败：" + exception.getMessage());
        }
    }

    private StoredObject upload(Path audioPath, Long taskId) {
        String objectKey = "asr/task-" + taskId + "/audio.wav";
        try (InputStream input = Files.newInputStream(audioPath)) {
            return storage.putObject(objectKey, input, Files.size(audioPath), "audio/wav");
        } catch (IOException exception) {
            throw new BizException(500, "上传 ASR 临时音频失败：" + exception.getMessage());
        }
    }

    private TencentAsrTaskResult poll(long taskId, Long taskRecordId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(properties.getTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            cancellation.checkVideoTask(taskRecordId);
            TencentAsrTaskResult result = parser.parse(transport.post("DescribeTaskStatus", payload(Map.of(
                    "TaskId", taskId))));
            cancellation.checkVideoTask(taskRecordId);
            if (result.status() == TencentAsrTaskResult.Status.SUCCEEDED) {
                return result;
            }
            if (result.status() == TencentAsrTaskResult.Status.FAILED) {
                throw new BizException(502, "腾讯云 ASR 识别失败：" + result.errorMessage());
            }
            waitBeforeNextPoll();
        }
        throw new BizException(504, "腾讯云 ASR 轮询超时");
    }

    private void waitBeforeNextPoll() {
        try {
            Thread.sleep(Math.max(100, properties.getPollIntervalMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "腾讯云 ASR 轮询被中断");
        }
    }

    private String createUrlPayload(String audioUrl) {
        Map<String, Object> values = baseCreatePayload();
        values.put("SourceType", 0);
        values.put("Url", audioUrl);
        return payload(values);
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
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("构造腾讯云 ASR 请求失败", exception);
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
