package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TaskRecordProjectionServiceImplTest {
    private final ProcessingTaskMapper processingTasks = mock(ProcessingTaskMapper.class);
    private final TaskRecordMapper taskRecords = mock(TaskRecordMapper.class);
    private final TaskRecordProjectionServiceImpl projection =
            new TaskRecordProjectionServiceImpl(processingTasks, taskRecords);

    @Test
    void projectsRetryStateAndAttemptCountToExternalTask() {
        ProcessingTask source = source(ProcessingTaskState.RETRY_WAIT);
        source.setAttemptCount(2);
        source.setErrorMessage("ASR timeout");
        TaskRecord target = target();
        when(processingTasks.selectById(99L)).thenReturn(source);
        when(taskRecords.selectById(11L)).thenReturn(target);

        projection.project(99L);

        assertThat(target.getTaskStatus()).isEqualTo(TaskStatus.RETRYING);
        assertThat(target.getRetryCount()).isEqualTo(2);
        assertThat(target.getErrorMessage()).isEqualTo("ASR timeout");
        verify(taskRecords).updateById(target);
    }

    @Test
    void projectsSuccessfulTerminalTimeAndClearsError() {
        ProcessingTask source = source(ProcessingTaskState.SUCCESS);
        LocalDateTime finished = LocalDateTime.of(2026, 8, 14, 20, 0);
        source.setFinishedTime(finished);
        TaskRecord target = target();
        target.setErrorMessage("old error");
        when(processingTasks.selectById(99L)).thenReturn(source);
        when(taskRecords.selectById(11L)).thenReturn(target);

        projection.project(99L);

        assertThat(target.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(target.getFinishedTime()).isEqualTo(finished);
        assertThat(target.getErrorMessage()).isNull();
    }

    @Test
    void ignoresDocumentTasksWithoutLookingUpTaskRecord() {
        ProcessingTask source = source(ProcessingTaskState.PROCESSING);
        source.setTaskType(ProcessingTaskType.DOCUMENT_INGEST);
        when(processingTasks.selectById(99L)).thenReturn(source);

        projection.project(99L);

        verify(taskRecords, never()).selectById(org.mockito.ArgumentMatchers.any());
    }

    private ProcessingTask source(ProcessingTaskState state) {
        ProcessingTask value = new ProcessingTask();
        value.setId(99L);
        value.setUserId(7L);
        value.setTaskType(ProcessingTaskType.VIDEO_ANALYSIS);
        value.setBusinessId(11L);
        value.setState(state);
        return value;
    }

    private TaskRecord target() {
        TaskRecord value = new TaskRecord();
        value.setId(11L);
        value.setUserId(7L);
        value.setRetryCount(0);
        return value;
    }
}
