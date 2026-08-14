package com.videomind.module.knowledge.retrieval;

import java.util.List;

public interface HybridSearchGateway {
    List<RetrievalCandidate> keywordSearch(Long userId, List<Long> knowledgeBaseIds, String query, int limit);

    List<RetrievalCandidate> vectorSearch(Long userId, List<Long> knowledgeBaseIds, float[] vector, int limit);
}
