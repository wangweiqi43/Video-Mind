package com.videomind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

public final class AiApiSupport {

    private AiApiSupport() {
    }

    public static void requireConfigured(String providerName, ApiProvider provider) {
        if (!StringUtils.hasText(provider.getEndpoint())) {
            throw new BizException(500, providerName + " API endpoint 未配置，请设置 application.yml 或环境变量。");
        }
        if (!StringUtils.hasText(provider.getApiKey())) {
            throw new BizException(500, providerName + " API key 未配置，请设置 application.yml 或环境变量。");
        }
    }

    public static void setBearerAuth(HttpHeaders headers, String apiKey) {
        if (StringUtils.hasText(apiKey)) {
            headers.setBearerAuth(apiKey);
        }
    }

    public static String firstText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = find(root, path);
            if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }

    public static List<Float> firstFloatArray(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = find(root, path);
            if (node != null && node.isArray()) {
                List<Float> values = new ArrayList<>();
                for (JsonNode item : node) {
                    values.add((float) item.asDouble());
                }
                return values;
            }
        }
        return List.of();
    }

    /**
     * Supports simple dotted paths and array indexes, e.g. choices[0].message.content.
     */
    private static JsonNode find(JsonNode root, String path) {
        if (root == null || !StringUtils.hasText(path)) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            String field = segment;
            Integer index = null;
            int bracketStart = segment.indexOf('[');
            if (bracketStart >= 0 && segment.endsWith("]")) {
                field = segment.substring(0, bracketStart);
                index = Integer.parseInt(segment.substring(bracketStart + 1, segment.length() - 1));
            }
            if (StringUtils.hasText(field)) {
                current = current.get(field);
            }
            if (index != null && current != null && current.isArray() && current.size() > index) {
                current = current.get(index);
            }
        }
        return current;
    }
}
