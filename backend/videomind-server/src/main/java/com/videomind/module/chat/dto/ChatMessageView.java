package com.videomind.module.chat.dto;

import com.videomind.common.enums.MessageRole;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatMessageView {
    private Long id;
    private Long sessionId;
    private Long userId;
    private Long generationId;
    private MessageRole role;
    private String content;
    private String referencesJson;
    private LocalDateTime createdTime;
    private ChatFeedbackResponse feedback;
}
