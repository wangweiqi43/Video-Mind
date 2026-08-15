package com.videomind.module.chat.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.exception.BizException;
import com.videomind.module.agent.audit.ChatGeneration;
import com.videomind.module.agent.audit.ChatGenerationMapper;
import org.junit.jupiter.api.Test;

class ChatGenerationCancellationServiceTest {
    private final ChatGenerationMapper generations = mock(ChatGenerationMapper.class);
    private final ChatGenerationCancellationRegistry registry = mock(ChatGenerationCancellationRegistry.class);
    private final ChatGenerationCancellationService service =
            new ChatGenerationCancellationService(generations, registry);

    @Test
    void requestsCancellationAndSignalsTheActiveGeneration() {
        when(generations.selectById(61L)).thenReturn(generation(7L, "RUNNING"),
                generation(7L, "CANCEL_REQUESTED"));
        when(generations.requestCancellation(any(), any(), any())).thenReturn(1);

        var result = service.requestCancellation(61L, 7L);

        assertThat(result.status()).isEqualTo("CANCEL_REQUESTED");
        verify(registry).requestCancellation(61L);
    }

    @Test
    void terminalCancellationIsIdempotent() {
        when(generations.selectById(61L)).thenReturn(generation(7L, "SUCCESS"));

        assertThat(service.requestCancellation(61L, 7L).status()).isEqualTo("SUCCESS");

        verify(generations, never()).requestCancellation(any(), any(), any());
        verify(registry, never()).requestCancellation(any());
    }

    @Test
    void hidesGenerationsOwnedByAnotherUser() {
        when(generations.selectById(61L)).thenReturn(generation(8L, "RUNNING"));

        assertThatThrownBy(() -> service.requestCancellation(61L, 7L))
                .isInstanceOf(BizException.class);
    }

    private static ChatGeneration generation(Long userId, String status) {
        ChatGeneration value = new ChatGeneration();
        value.setId(61L);
        value.setUserId(userId);
        value.setStatus(status);
        return value;
    }
}
