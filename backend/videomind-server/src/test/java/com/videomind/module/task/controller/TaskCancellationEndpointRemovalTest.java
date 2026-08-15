package com.videomind.module.task.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class TaskCancellationEndpointRemovalTest {

    @Test
    void taskControllerDoesNotExposeProcessingCancellation() {
        boolean mapped = Arrays.stream(TaskController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(annotation -> annotation != null)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch("/{taskId}/cancel"::equals);

        assertThat(mapped).isFalse();
    }
}
