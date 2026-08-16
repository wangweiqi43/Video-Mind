package com.videomind.module.chat.service;

import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.dto.ChatMessageView;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    ChatSessionCreateResponse createSession(Long videoId, List<Long> knowledgeBaseIds, Long userId);

    List<ChatSessionResponse> listSessions(Long videoId, Long userId);

    ChatMessageResponse sendMessage(ChatMessageRequest request, Long userId);

    SseEmitter streamMessage(ChatMessageRequest request, Long userId);

    List<ChatMessageView> listMessages(Long sessionId, Long videoId, Long userId);
}
