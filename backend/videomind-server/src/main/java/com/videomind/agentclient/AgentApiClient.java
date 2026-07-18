package com.videomind.agentclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import com.videomind.module.agent.service.MindAgentBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentApiClient {

    private final HttpClient agentHttpClient;
    private final ObjectMapper objectMapper;
    private final AgentClientProperties properties;
    private final MindAgentBindingService bindingService;

    public AgentApiClient(HttpClient httpClient, ObjectMapper objectMapper, AgentClientProperties properties) {
        this(httpClient, objectMapper, properties, null);
    }

    @Autowired
    public AgentApiClient(HttpClient httpClient, ObjectMapper objectMapper, AgentClientProperties properties,
                          MindAgentBindingService bindingService) {
        this.agentHttpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.bindingService = bindingService;
    }

    public JsonNode post(String path, Object payload, AgentRequestContext context) {
        String body = serialize(payload);
        HttpResponse<String> response = sendWithRetry(path, body, context, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new AgentClientException("INVALID_RESPONSE", "Agent Platform 返回了无法解析的 JSON", ex, false);
        }
    }

    public JsonNode get(String path, AgentRequestContext context) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path)).timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .header("Accept", "application/json").header("X-Trace-Id", context.traceId()).GET();
            String accessToken = bindingService == null ? properties.getApiKey() : bindingService.accessToken(context.userId());
            if (StringUtils.hasText(accessToken)) builder.header("Authorization", "Bearer " + accessToken);
            HttpResponse<String> response = agentHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response.statusCode(), response.body());
            return objectMapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentClientException("INTERRUPTED", "Agent Platform 请求被中断", ex, true);
        } catch (IOException ex) {
            throw new AgentClientException("NETWORK_ERROR", "Agent Platform 网络请求失败", ex, true);
        }
    }

    public void postSse(String path, Object payload, AgentRequestContext context, Consumer<AgentSseEvent> consumer) {
        String body = serialize(payload);
        HttpResponse<InputStream> response = sendWithRetry(path, body, context, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream input = response.body()) {
                ensureSuccess(response.statusCode(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new AgentClientException("READ_ERROR", "读取 Agent Platform 错误响应失败", ex, true);
            }
        }
        parseSse(response.body(), consumer);
    }

    public String sign(String method, String path, String timestamp, String body) {
        if (!StringUtils.hasText(properties.getSigningSecret())) {
            return "";
        }
        String canonical = timestamp + "\n" + method.toUpperCase() + "\n" + path + "\n" + body;
        return hmacSha256(properties.getSigningSecret(), canonical);
    }

    private <T> HttpResponse<T> sendWithRetry(
            String path,
            String body,
            AgentRequestContext context,
            HttpResponse.BodyHandler<T> bodyHandler
    ) {
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<T> response = agentHttpClient.send(buildRequest(path, body, context), bodyHandler);
                if (!isRetryable(response.statusCode()) || attempt == attempts) {
                    return response;
                }
                closeQuietly(response.body());
                backoff(attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AgentClientException("INTERRUPTED", "Agent Platform 请求被中断", ex, true);
            } catch (IOException ex) {
                if (attempt == attempts) {
                    throw new AgentClientException("NETWORK_ERROR", "Agent Platform 网络请求失败：" + ex.getMessage(), ex, true);
                }
                backoff(attempt);
            }
        }
        throw new AgentClientException("REQUEST_FAILED", 500, "Agent Platform 请求失败", true);
    }

    private HttpRequest buildRequest(String path, String body, AgentRequestContext context) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("Idempotency-Key", context.idempotencyKey())
                .header("X-Trace-Id", context.traceId())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String accessToken = bindingService == null ? properties.getApiKey() : bindingService.accessToken(context.userId());
        if (StringUtils.hasText(accessToken)) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder.build();
    }

    private URI resolve(String path) {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + normalizedPath);
    }

    private void parseSse(InputStream inputStream, Consumer<AgentSseEvent> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emit(consumer, event, data);
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            emit(consumer, event, data);
        } catch (IOException ex) {
            throw new AgentClientException("SSE_READ_ERROR", "读取 Agent Platform SSE 失败：" + ex.getMessage(), ex, true);
        }
    }

    private void emit(Consumer<AgentSseEvent> consumer, String event, StringBuilder data) {
        if (!data.isEmpty() && !"[DONE]".contentEquals(data)) {
            consumer.accept(new AgentSseEvent(event, data.toString()));
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new AgentClientException("SERIALIZE_ERROR", "序列化 Agent Platform 请求失败", ex, false);
        }
    }

    private void ensureSuccess(int status, String body) {
        if (status >= 200 && status < 300) {
            return;
        }
        String code = "AGENT_HTTP_" + status;
        String message = "Agent Platform 请求失败（HTTP " + status + "）";
        try {
            JsonNode error = objectMapper.readTree(body);
            code = error.path("errorCode").asText(error.path("code").asText(code));
            message = error.path("message").asText(message);
        } catch (Exception ignored) {
            if (StringUtils.hasText(body)) {
                message += "：" + body.substring(0, Math.min(body.length(), 300));
            }
        }
        throw new AgentClientException(code, status, message, isRetryable(status));
    }

    private boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(1000L, 150L * attempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentClientException("INTERRUPTED", "Agent Platform 重试等待被中断", ex, true);
        }
    }

    private void closeQuietly(Object body) {
        if (body instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best-effort release before retrying the request.
            }
        }
    }

    private String hmacSha256(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AgentClientException("SIGNING_ERROR", "生成 Agent Platform 请求签名失败", ex, false);
        }
    }

    public record AgentSseEvent(String event, String data) {
    }
}
