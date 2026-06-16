package com.videomind.module.knowledge.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.config.KnowledgeProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.embedding", name = "mode", havingValue = "real")
public class RealEmbeddingClient implements EmbeddingClient {

    private final AiProperties aiProperties;
    private final KnowledgeProperties knowledgeProperties;
    private final RestClient aiRestClient;

    @Override
    public float[] embed(String text) {
        AiProperties.EmbeddingProvider embedding = aiProperties.getEmbedding();
        AiApiSupport.requireConfigured("Embedding", embedding);

        JsonNode response = aiRestClient.post()
                .uri(embedding.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, embedding.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildRequest(text, embedding))
                .retrieve()
                .body(JsonNode.class);

        return parseVector(response);
    }

    private Map<String, Object> buildRequest(String text, AiProperties.EmbeddingProvider embedding) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (StringUtils.hasText(embedding.getModel())) {
            request.put("model", embedding.getModel());
        }
        request.put("input", text == null ? "" : text);
        return request;
    }

    private float[] parseVector(JsonNode response) {
        List<Float> values = AiApiSupport.firstFloatArray(
                response,
                "data[0].embedding",
                "embedding",
                "vector",
                "data.vector"
        );
        if (values.isEmpty()) {
            throw new BizException(500, "Embedding API 响应中没有找到向量，请调整 RealEmbeddingClient.parseVector 字段映射。");
        }

        int expectedDim = knowledgeProperties.getEmbeddingDim();
        if (values.size() != expectedDim) {
            throw new BizException(500, "Embedding 向量维度为 " + values.size()
                    + "，但知识库配置 KNOWLEDGE_EMBEDDING_DIM=" + expectedDim + "，请保持一致。");
        }

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }
}
