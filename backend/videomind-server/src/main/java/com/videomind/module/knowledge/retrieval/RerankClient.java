package com.videomind.module.knowledge.retrieval;

import java.util.List;

public interface RerankClient {
    List<RerankScore> rerank(String query, List<String> documents, int topN);

    record RerankScore(int index, double score) {
    }
}
