package com.videomind.module.chat.generation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class ChatGenerationCancellationRegistry {
    private final ConcurrentMap<Long, ChatGenerationCancellationToken> active = new ConcurrentHashMap<>();

    public ChatGenerationCancellationToken activate(Long generationId) {
        ChatGenerationCancellationToken token = new ChatGenerationCancellationToken(generationId);
        ChatGenerationCancellationToken existing = active.putIfAbsent(generationId, token);
        return existing == null ? token : existing;
    }

    public void requestCancellation(Long generationId) {
        ChatGenerationCancellationToken token = active.get(generationId);
        if (token != null) {
            token.cancel();
        }
    }

    public void release(Long generationId) {
        active.remove(generationId);
    }
}
