package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatSessionCreateRequest {

    @NotNull
    private Long videoId;
}
