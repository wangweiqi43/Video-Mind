package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import jakarta.validation.constraints.Pattern;

@Data
public class ChatSessionCreateRequest {

    @NotNull
    private Long videoId;

    @Pattern(regexp = "NORMAL|ADVANCED")
    private String applicationMode = "NORMAL";
}
