package com.videomind.module.knowledge.service;

import com.videomind.module.knowledge.dto.KnowledgeBaseResponse;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import java.util.List;

public interface KnowledgeBaseService {
    KnowledgeBaseResponse createUserKnowledgeBase(Long userId, String name);

    KnowledgeBase ensureVideoKnowledgeBase(Long userId, Long videoId, String videoName);

    List<KnowledgeBaseResponse> list(Long userId);

    KnowledgeBaseResponse get(Long userId, Long knowledgeBaseId);

    List<Long> requireReadyConversationScope(Long userId, Long videoId, List<Long> selectedKnowledgeBaseIds);
}
