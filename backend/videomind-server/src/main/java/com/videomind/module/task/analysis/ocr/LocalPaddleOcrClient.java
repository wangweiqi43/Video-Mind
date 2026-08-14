package com.videomind.module.task.analysis.ocr;

import com.videomind.common.exception.BizException;
import com.videomind.config.OcrProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ocr", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalPaddleOcrClient implements FrameOcrClient {
    private final OcrProperties properties;
    private final LocalOcrResponseParser parser;
    private final OkHttpClient client;

    public LocalPaddleOcrClient(OcrProperties properties, LocalOcrResponseParser parser) {
        this.properties = properties;
        this.parser = parser;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    @Override
    public OcrText recognize(Path imagePath) {
        try {
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", imagePath.getFileName().toString(),
                            RequestBody.create(Files.readAllBytes(imagePath), MediaType.parse("image/jpeg")))
                    .build();
            Request request = new Request.Builder().url(properties.getEndpoint()).post(body).build();
            try (Response response = client.newCall(request).execute()) {
                String json = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new BizException(502, "本机 OCR 请求失败：HTTP " + response.code());
                }
                return parser.parse(json);
            }
        } catch (IOException exception) {
            throw new BizException(502, "本机 OCR 网络异常：" + exception.getMessage());
        }
    }
}
