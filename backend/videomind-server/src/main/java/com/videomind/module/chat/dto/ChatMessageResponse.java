package com.videomind.module.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatMessageResponse {

    private Long messageId;
    private Long generationId;
    private String answer;
    private List<RagReference> references;
    private String referencesJson;
    private ChatFeedbackResponse feedback;
    private LocalDateTime createdTime;
}
