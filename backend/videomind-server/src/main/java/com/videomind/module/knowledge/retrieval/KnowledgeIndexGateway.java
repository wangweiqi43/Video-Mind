package com.videomind.module.knowledge.retrieval;

import java.util.List;

public interface KnowledgeIndexGateway {
    void ensureIndex();

    void stage(List<IndexedChunk> chunks);

    long countVersion(Long documentVersionId, boolean published);

    void publishVersion(Long documentVersionId);

    void deleteDocument(Long documentId);

    void deleteKnowledgeBase(Long knowledgeBaseId);

    void deleteOtherVersions(Long documentId, Long currentVersionId);

    record IndexedChunk(Long userId, RetrievalCandidate candidate, float[] vector, String sourceType) {
    }
}
