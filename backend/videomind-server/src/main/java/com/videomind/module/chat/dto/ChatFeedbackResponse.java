package com.videomind.module.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatFeedbackResponse {
    private Long messageId;
    private String rating;
    private List<String> reasonCodes;
    private String detail;
    private LocalDateTime updatedTime;
}
