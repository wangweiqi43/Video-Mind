package com.videomind.module.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatSessionResponse {

    private Long id;
    private Long videoId;
    private String title;
    private String lastMessagePreview;
    private List<Long> knowledgeBaseIds;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
