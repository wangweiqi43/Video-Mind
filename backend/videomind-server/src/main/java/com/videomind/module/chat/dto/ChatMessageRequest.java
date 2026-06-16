package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatMessageRequest {

    @NotNull
    private Long sessionId;

    @NotNull
    private Long videoId;

    @NotBlank
    private String question;
}
