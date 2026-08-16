package com.videomind.module.chat.service;

import com.videomind.module.chat.dto.ChatFeedbackRequest;
import com.videomind.module.chat.dto.ChatFeedbackResponse;
import java.util.Map;

public interface ChatFeedbackService {
    ChatFeedbackResponse save(Long messageId, Long userId, ChatFeedbackRequest request);
    void delete(Long messageId, Long userId);
    Map<Long, ChatFeedbackResponse> findBySession(Long sessionId, Long userId);
}
