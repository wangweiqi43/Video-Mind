package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChatMessageRequest {

    @NotNull
    private Long sessionId;

    @NotNull
    private Long videoId;

    @NotBlank
    private String question;

    @Pattern(regexp = "KNOWLEDGE_ONLY|KNOWLEDGE_EXTENDED", message = "必须为 KNOWLEDGE_ONLY 或 KNOWLEDGE_EXTENDED")
    private String answerScope = "KNOWLEDGE_EXTENDED";

    private Boolean webSearchEnabled = false;

    private Boolean deepThinkingEnabled = false;
}
