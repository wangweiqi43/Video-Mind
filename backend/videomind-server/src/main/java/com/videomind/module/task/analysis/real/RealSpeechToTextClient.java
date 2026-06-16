package com.videomind.module.task.analysis.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.asr", name = "mode", havingValue = "real")
public class RealSpeechToTextClient implements SpeechToTextClient {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Override
    public AsrResult transcribe(AudioExtractionResult audio, VideoFile videoFile, TaskRecord taskRecord) {
        AiProperties.ApiProvider asr = aiProperties.getAsr();
        AiApiSupport.requireConfigured("ASR", asr);

        JsonNode response = executeRequest(audio, videoFile, taskRecord, asr);

        return parseResponse(response);
    }

    private JsonNode executeRequest(
            AudioExtractionResult audio,
            VideoFile videoFile,
            TaskRecord taskRecord,
            AiProperties.ApiProvider asr
    ) {
        File audioFile = Path.of(audio.getAudioPath()).toFile();
        if (!audioFile.exists()) {
            throw new BizException(500, "ASR 音频文件不存在：" + audio.getAudioPath());
        }

        MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "file",
                        audioFile.getName(),
                        RequestBody.create(audioFile, MediaType.parse("audio/wav"))
                );
        if (StringUtils.hasText(asr.getModel())) {
            multipartBuilder.addFormDataPart("model", asr.getModel());
        }
        multipartBuilder.addFormDataPart("language", "zh");

        Request request = new Request.Builder()
                .url(asr.getEndpoint())
                .addHeader("Authorization", "Bearer " + asr.getApiKey())
                .post(multipartBuilder.build())
                .build();

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(Math.max(300, asr.getTimeoutSeconds())))
                .writeTimeout(Duration.ofSeconds(Math.max(300, asr.getTimeoutSeconds())))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BizException(response.code(), "ASR API 请求失败：" + body);
            }
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new BizException(500, "ASR API 请求异常：" + e.getMessage());
        }
    }

    private AsrResult parseResponse(JsonNode response) {
        String text = AiApiSupport.firstText(response, "text", "data.text", "result.text");
        if (!StringUtils.hasText(text)) {
            throw new BizException(500, "ASR API 响应中没有找到转录文本，请调整 RealSpeechToTextClient.parseResponse 字段映射。");
        }
        String language = AiApiSupport.firstText(response, "language", "data.language", "result.language");
        return AsrResult.builder()
                .language(StringUtils.hasText(language) ? language : "zh-CN")
                .text(text)
                .build();
    }
}
