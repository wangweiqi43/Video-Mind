package com.videomind.module.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurn {

    @JsonProperty("user_message_id")
    private Long userMessageId;
    @JsonProperty("assistant_message_id")
    private Long assistantMessageId;
    private String question;
    private String answer;
}
