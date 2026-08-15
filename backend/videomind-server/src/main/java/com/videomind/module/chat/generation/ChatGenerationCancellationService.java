package com.videomind.module.chat.generation;

import com.videomind.common.exception.BizException;
import com.videomind.module.agent.audit.ChatGeneration;
import com.videomind.module.agent.audit.ChatGenerationMapper;
import com.videomind.module.chat.dto.ChatGenerationStatusResponse;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatGenerationCancellationService {
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "CANCELLED");
    private final ChatGenerationMapper generations;
    private final ChatGenerationCancellationRegistry registry;

    public ChatGenerationStatusResponse requestCancellation(Long generationId, Long userId) {
        ChatGeneration generation = requireOwned(generationId, userId);
        if (!TERMINAL.contains(generation.getStatus()) && !"CANCEL_REQUESTED".equals(generation.getStatus())) {
            generations.requestCancellation(generationId, userId, LocalDateTime.now());
            generation = requireOwned(generationId, userId);
        }
        if ("CANCEL_REQUESTED".equals(generation.getStatus())) {
            registry.requestCancellation(generationId);
        }
        return new ChatGenerationStatusResponse(generationId, generation.getStatus());
    }

    private ChatGeneration requireOwned(Long generationId, Long userId) {
        ChatGeneration generation = generations.selectById(generationId);
        if (generation == null || !userId.equals(generation.getUserId())) {
            throw new BizException(404, "生成任务不存在或无权访问");
        }
        return generation;
    }
}
