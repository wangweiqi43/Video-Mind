package com.videomind.module.knowledge.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.AiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class QwenVisionClient implements VisionClient {
    static final String PROMPT = "请客观描述这张图片的内容，包括场景、对象、布局、颜色和清晰可见的文字信息；"
            + "不得推测不可见信息；直接输出纯文本描述，不要多余说明。";
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().build();

    public QwenVisionClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public VisionResult describe(byte[] image, String mediaType) {
        AiProperties.VisionProvider config = properties.getVision();
        if (!"real".equalsIgnoreCase(config.getMode()) || !StringUtils.hasText(config.getApiKey())
                || !StringUtils.hasText(config.getEndpoint())) {
            return VisionResult.degraded(config.getModel(), "VISION_NOT_CONFIGURED");
        }
        int attempts = Math.max(1, config.getMaxAttempts() == null ? 2 : config.getMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(image);
                Map<String, Object> body = Map.of(
                        "model", config.getModel(), "temperature", 0,
                        "messages", List.of(Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", PROMPT),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.getEndpoint()))
                        .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())))
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) continue;
                JsonNode root = objectMapper.readTree(response.body());
                String text = root.path("choices").path(0).path("message").path("content").asText().trim();
                if (StringUtils.hasText(text)) return VisionResult.success(text, config.getModel());
            } catch (Exception ignored) {
                if (attempt == attempts) return VisionResult.degraded(config.getModel(), "VISION_CALL_FAILED");
            }
        }
        return VisionResult.degraded(config.getModel(), "VISION_EMPTY_RESPONSE");
    }
}
