package com.videomind.module.chat.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.module.chat.dto.ChatMessageRequest;
import com.videomind.module.chat.dto.ChatMessageResponse;
import com.videomind.module.chat.dto.ChatSessionCreateRequest;
import com.videomind.module.chat.dto.ChatSessionCreateResponse;
import com.videomind.module.chat.dto.ChatSessionResponse;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/session")
    public ApiResponse<ChatSessionCreateResponse> createSession(@Valid @RequestBody ChatSessionCreateRequest request) {
        return ApiResponse.success(chatService.createSession(request.getVideoId(), MockUserContext.currentUserId()));
    }

    @GetMapping("/session/list")
    public ApiResponse<List<ChatSessionResponse>> listSessions(@RequestParam Long videoId) {
        return ApiResponse.success(chatService.listSessions(videoId, MockUserContext.currentUserId()));
    }

    @PostMapping("/message")
    public ApiResponse<ChatMessageResponse> sendMessage(@Valid @RequestBody ChatMessageRequest request) {
        return ApiResponse.success(chatService.sendMessage(request, MockUserContext.currentUserId()));
    }

    @PostMapping("/message/stream")
    public SseEmitter streamMessage(@Valid @RequestBody ChatMessageRequest request) {
        return chatService.streamMessage(request, MockUserContext.currentUserId());
    }

    @GetMapping("/message/stream")
    public SseEmitter streamMessage(
            @RequestParam Long sessionId,
            @RequestParam Long videoId,
            @RequestParam String question,
            @RequestParam(defaultValue = "KNOWLEDGE_EXTENDED") String answerScope,
            @RequestParam(defaultValue = "NORMAL") String applicationMode
    ) {
        ChatMessageRequest request = new ChatMessageRequest();
        request.setSessionId(sessionId);
        request.setVideoId(videoId);
        request.setQuestion(question);
        request.setAnswerScope(answerScope);
        request.setApplicationMode(applicationMode);
        return chatService.streamMessage(request, MockUserContext.currentUserId());
    }

    @GetMapping("/session/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> listMessages(
            @PathVariable Long sessionId,
            @RequestParam Long videoId
    ) {
        return ApiResponse.success(chatService.listMessages(sessionId, videoId, MockUserContext.currentUserId()));
    }
}
