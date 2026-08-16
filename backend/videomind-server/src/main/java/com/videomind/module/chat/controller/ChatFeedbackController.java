package com.videomind.module.chat.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.chat.dto.ChatFeedbackRequest;
import com.videomind.module.chat.dto.ChatFeedbackResponse;
import com.videomind.module.chat.service.ChatFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/messages")
public class ChatFeedbackController {
    private final ChatFeedbackService feedback;

    @PutMapping("/{messageId}/feedback")
    public ApiResponse<ChatFeedbackResponse> save(@PathVariable Long messageId,
                                                   @Valid @RequestBody ChatFeedbackRequest request) {
        return ApiResponse.success(feedback.save(messageId, MockUserContext.currentUserId(), request));
    }

    @DeleteMapping("/{messageId}/feedback")
    public ApiResponse<Void> delete(@PathVariable Long messageId) {
        feedback.delete(messageId, MockUserContext.currentUserId());
        return ApiResponse.success(null);
    }
}
