package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.service.ProcessingTaskStateMachine.LeaseStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ProcessingTaskStateMachineImplTest {
    private final ProcessingTaskMapper mapper = mock(ProcessingTaskMapper.class);
    private final ProcessingTaskStateMachineImpl service = new ProcessingTaskStateMachineImpl(mapper);

    @Test
    void acquiresExpiredLeaseWithVersionCas() {
        ProcessingTask task = task(ProcessingTaskState.PROCESSING, 4L);
        task.setLeaseOwner("dead-worker");
        task.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectById(9L)).thenReturn(task);
        when(mapper.acquireLease(eq(9L), eq(4L), eq("worker-2"), eq("PARSE"), any(), any()))
                .thenReturn(1);

        var result = service.acquire(9L, "worker-2", "PARSE", Duration.ofMinutes(2));

        assertThat(result.status()).isEqualTo(LeaseStatus.ACQUIRED);
        assertThat(result.stateVersion()).isEqualTo(5L);
    }

    @Test
    void refusesUnexpiredLeaseHeldByAnotherWorker() {
        ProcessingTask task = task(ProcessingTaskState.PROCESSING, 8L);
        task.setLeaseOwner("worker-1");
        task.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(mapper.selectById(9L)).thenReturn(task);

        var result = service.acquire(9L, "worker-2", "PARSE", Duration.ofMinutes(2));

        assertThat(result.status()).isEqualTo(LeaseStatus.BUSY);
        verify(mapper, never()).acquireLease(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void marksRetryExhaustedTaskDeadWithoutRunningBusinessCode() {
        ProcessingTask task = task(ProcessingTaskState.RETRY_WAIT, 2L);
        task.setAttemptCount(5);
        task.setMaxAttempts(5);
        task.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectById(9L)).thenReturn(task);
        when(mapper.markTerminal(eq(9L), eq(2L), eq(null), eq("DEAD"), any(),
                eq("RETRY_EXHAUSTED"), any(), any())).thenReturn(1);

        var result = service.acquire(9L, "worker", "PARSE", Duration.ofMinutes(2));

        assertThat(result.status()).isEqualTo(LeaseStatus.RETRY_EXHAUSTED);
        verify(mapper, never()).acquireLease(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void completionRequiresMatchingOwnerAndVersionAtDatabaseBoundary() {
        when(mapper.markSuccess(eq(9L), eq(7L), eq("worker"), eq("PUBLISHED"), any()))
                .thenReturn(0, 1);
        assertThat(service.succeed(9L, "worker", 7L, "PUBLISHED")).isFalse();
        assertThat(service.succeed(9L, "worker", 7L, "PUBLISHED")).isTrue();
    }

    private static ProcessingTask task(ProcessingTaskState state, Long version) {
        ProcessingTask task = new ProcessingTask();
        task.setId(9L);
        task.setState(state);
        task.setStage("START");
        task.setStateVersion(version);
        task.setAttemptCount(0);
        task.setMaxAttempts(5);
        return task;
    }
}
