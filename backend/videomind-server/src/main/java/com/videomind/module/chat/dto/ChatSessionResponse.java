package com.videomind.module.chat.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatSessionResponse {

    private Long id;
    private Long videoId;
    private String title;
    private String lastMessagePreview;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
