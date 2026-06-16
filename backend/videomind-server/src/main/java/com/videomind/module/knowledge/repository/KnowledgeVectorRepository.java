package com.videomind.module.knowledge.repository;

import com.videomind.module.knowledge.dto.KnowledgeChunk;
import java.util.List;

public interface KnowledgeVectorRepository {

    void saveChunks(Long taskId, List<KnowledgeChunk> chunks, List<float[]> embeddings);

    long countChunks(Long taskId);

    void deleteChunks(Long taskId);
}
