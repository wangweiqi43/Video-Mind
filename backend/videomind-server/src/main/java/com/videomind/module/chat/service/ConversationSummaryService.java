package com.videomind.module.chat.service;

import com.videomind.module.chat.entity.ConversationSummary;

public interface ConversationSummaryService {

    ConversationSummary getActiveSummary(Long conversationId);

    boolean compressIfNeeded(Long conversationId, Long userId);
}
