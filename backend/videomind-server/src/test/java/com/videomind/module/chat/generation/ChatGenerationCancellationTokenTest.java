package com.videomind.module.chat.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatGenerationCancellationTokenTest {

    @Test
    void cancellationRunsTheTransportHookOnceAndMakesChecksFail() {
        ChatGenerationCancellationRegistry registry = new ChatGenerationCancellationRegistry();
        ChatGenerationCancellationToken token = registry.activate(61L);
        AtomicInteger hooks = new AtomicInteger();
        token.onCancel(hooks::incrementAndGet);

        registry.requestCancellation(61L);
        registry.requestCancellation(61L);

        assertThat(hooks).hasValue(1);
        assertThat(token.cancellationRequested()).isTrue();
        assertThatThrownBy(token::check).isInstanceOf(ChatGenerationCancelledException.class);
    }

    @Test
    void lateTransportHookRunsImmediatelyAfterCancellation() {
        ChatGenerationCancellationRegistry registry = new ChatGenerationCancellationRegistry();
        ChatGenerationCancellationToken token = registry.activate(61L);
        registry.requestCancellation(61L);
        AtomicInteger hooks = new AtomicInteger();

        token.onCancel(hooks::incrementAndGet);

        assertThat(hooks).hasValue(1);
    }
}
