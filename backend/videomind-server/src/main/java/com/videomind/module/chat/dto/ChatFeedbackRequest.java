package com.videomind.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ChatFeedbackRequest {
    @NotBlank
    @Pattern(regexp = "UP|DOWN")
    private String rating;

    @Size(max = 6)
    private List<String> reasonCodes = List.of();

    @Size(max = 500)
    private String detail;
}
