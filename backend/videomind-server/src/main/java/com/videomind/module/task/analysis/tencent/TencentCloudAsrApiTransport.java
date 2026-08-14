package com.videomind.module.task.analysis.tencent;

import com.videomind.common.exception.BizException;
import com.videomind.config.TencentAsrProperties;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${videomind.ai.asr.mode:mock}' == 'real' && '${videomind.ai.asr.provider:generic}' == 'tencent'")
public class TencentCloudAsrApiTransport implements TencentAsrApiTransport {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final TencentAsrProperties properties;
    private final TencentCloudTc3Signer signer;
    private final OkHttpClient client;

    public TencentCloudAsrApiTransport(TencentAsrProperties properties, TencentCloudTc3Signer signer) {
        this.properties = properties;
        this.signer = signer;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Override
    public String post(String action, String payload) {
        TencentCloudTc3Signer.SignedHeaders headers = signer.sign(action, payload, Instant.now());
        Request request = new Request.Builder()
                .url(properties.getEndpoint())
                .header("Host", headers.host())
                .header("Content-Type", headers.contentType())
                .header("X-TC-Action", headers.action())
                .header("X-TC-Version", headers.version())
                .header("X-TC-Region", headers.region())
                .header("X-TC-Timestamp", headers.timestamp())
                .header("Authorization", headers.authorization())
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BizException(502, "腾讯云 ASR HTTP 请求失败：" + response.code());
            }
            return body;
        } catch (IOException exception) {
            throw new BizException(502, "腾讯云 ASR 网络异常：" + exception.getMessage());
        }
    }
}
