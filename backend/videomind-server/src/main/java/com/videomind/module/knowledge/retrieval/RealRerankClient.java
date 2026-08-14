package com.videomind.module.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.rerank", name = "mode", havingValue = "real")
public class RealRerankClient implements RerankClient {
    private final AiProperties aiProperties;
    private final RestClient aiRestClient;

    @Override
    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        AiProperties.ApiProvider provider = aiProperties.getRerank();
        AiApiSupport.requireConfigured("Rerank", provider);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topN);
        body.put("return_documents", false);
        JsonNode response = aiRestClient.post().uri(provider.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, provider.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        return parse(response, documents.size(), topN);
    }

    static List<RerankScore> parse(JsonNode response, int documentCount, int topN) {
        if (response == null || !response.path("results").isArray()) {
            throw new BizException(503, "BGE Reranker 未返回有效结果");
        }
        List<RerankScore> values = new ArrayList<>();
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= documentCount || !item.has("relevance_score")) {
                continue;
            }
            values.add(new RerankScore(index, item.path("relevance_score").asDouble()));
            if (values.size() == topN) {
                break;
            }
        }
        if (values.isEmpty()) {
            throw new BizException(503, "BGE Reranker 未返回有效结果");
        }
        return List.copyOf(values);
    }
}
