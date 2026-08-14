package com.videomind.module.chat.service;

import com.videomind.module.chat.dto.ConversationContext;
import java.util.List;

public interface ConversationContextService {

    ConversationContext getContext(Long conversationId, Long userId, List<Long> knowledgeBaseIds);

    void refreshContext(Long conversationId, Long userId, List<Long> knowledgeBaseIds);

    void evictContext(Long conversationId);
}
