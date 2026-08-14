package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ChatSessionCreateRequest {

    @NotNull
    private Long videoId;

    @Size(max = 20)
    private List<Long> knowledgeBaseIds = List.of();
}
