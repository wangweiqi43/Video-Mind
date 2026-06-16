package com.videomind.module.knowledge.repository;

import com.videomind.module.knowledge.dto.KnowledgeSearchResult;
import java.util.List;

public interface KnowledgeSearchRepository {

    List<KnowledgeSearchResult> search(Long userId, Long videoId, float[] queryEmbedding, int topK);
}
