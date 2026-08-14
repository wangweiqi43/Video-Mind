package com.videomind.module.chat.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatSessionCreateResponse {

    private Long sessionId;
    private Long videoId;
    private String title;
    private List<Long> knowledgeBaseIds;
}
