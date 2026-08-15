package com.videomind.module.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class ChatGenerationCancellationEndpointTest {

    @Test
    void exposesGenerationCancellationInsteadOfProcessingTaskCancellation() {
        boolean mapped = Arrays.stream(ChatController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/generations/{generationId}/cancel"::equals);

        assertThat(mapped).isTrue();
    }
}
