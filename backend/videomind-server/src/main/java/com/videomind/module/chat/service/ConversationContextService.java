package com.videomind.module.chat.service;

import com.videomind.module.chat.dto.ConversationContext;

public interface ConversationContextService {

    ConversationContext getContext(Long conversationId, Long userId);

    void refreshContext(Long conversationId, Long userId);

    void evictContext(Long conversationId);
}
