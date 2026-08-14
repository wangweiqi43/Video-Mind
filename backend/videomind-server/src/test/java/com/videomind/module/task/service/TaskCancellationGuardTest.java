package com.videomind.module.task.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import org.junit.jupiter.api.Test;

class TaskCancellationGuardTest {
    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final TaskCancellationGuard guard = new TaskCancellationGuard(tasks);

    @Test
    void rejectsRequestedProcessingTaskAtStageBoundary() {
        ProcessingTask task = new ProcessingTask();
        task.setState(ProcessingTaskState.CANCEL_REQUESTED);
        when(tasks.selectById(9L)).thenReturn(task);

        assertThatThrownBy(() -> guard.checkProcessingTask(9L))
                .isInstanceOf(TaskCancellationException.class);
    }

    @Test
    void allowsActiveVideoTaskAndRejectsCancelledVideoTask() {
        ProcessingTask task = new ProcessingTask();
        task.setState(ProcessingTaskState.PROCESSING);
        when(tasks.selectOne(any())).thenReturn(task);
        assertThatCode(() -> guard.checkVideoTask(11L)).doesNotThrowAnyException();

        task.setState(ProcessingTaskState.CANCELLED);
        assertThatThrownBy(() -> guard.checkVideoTask(11L))
                .isInstanceOf(TaskCancellationException.class);
    }
}
