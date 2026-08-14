package com.videomind.module.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.rerank", name = "mode", havingValue = "mock",
        matchIfMissing = true)
public class MockRerankClient implements RerankClient {
    @Override
    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<RerankScore> values = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            String document = documents.get(index) == null ? "" : documents.get(index).toLowerCase(Locale.ROOT);
            double score = normalized.isBlank() ? 0 : document.contains(normalized) ? 1 : 0.5;
            values.add(new RerankScore(index, score));
        }
        return values.stream().sorted(Comparator.comparingDouble(RerankScore::score).reversed()
                        .thenComparingInt(RerankScore::index))
                .limit(topN).toList();
    }
}
