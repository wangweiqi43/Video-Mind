package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskTransactionContext;
import com.videomind.module.task.service.TaskTargetLifecycle;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalTaskTransactionServiceImplTest {
    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final MqTransactionEventMapper events = mock(MqTransactionEventMapper.class);
    private final TaskRecordMapper taskRecords = mock(TaskRecordMapper.class);
    private final TaskTargetLifecycle targetLifecycle = mock(TaskTargetLifecycle.class);
    private final LocalTaskTransactionServiceImpl service = new LocalTaskTransactionServiceImpl(
            tasks, events, taskRecords, new ObjectMapper(), targetLifecycle);

    @Test
    void insertsTaskAndCommitEvidenceInOneLocalTransaction() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(1);
        TaskTransactionContext context = context(101L);

        var result = service.createOrReuse(context);

        assertThat(result.processingTaskId()).isEqualTo(101L);
        assertThat(result.businessId()).isEqualTo(31L);
        assertThat(result.reused()).isFalse();
        verify(events).insert(any(MqTransactionEvent.class));
        verify(targetLifecycle).onTaskCreated(any(), any());
        assertThat(context.getResolvedProcessingTaskId()).isEqualTo(101L);
        assertThat(context.getResolvedBusinessId()).isEqualTo(31L);
    }

    @Test
    void insertIgnoreRaceReusesWinnerWithoutRecordingDuplicateEvent() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(0);
        ProcessingTask winner = new ProcessingTask();
        winner.setId(88L);
        winner.setEventId("event-winner");
        winner.setBusinessId(31L);
        when(tasks.selectOne(any())).thenReturn(winner);
        TaskTransactionContext context = context(102L);

        var result = service.createOrReuse(context);

        assertThat(result.processingTaskId()).isEqualTo(88L);
        assertThat(result.eventId()).isEqualTo("event-winner");
        assertThat(result.businessId()).isEqualTo(31L);
        assertThat(result.reused()).isTrue();
        verify(events, never()).insert(any(MqTransactionEvent.class));
        assertThat(context.getResolvedEventId()).isEqualTo("event-winner");
    }

    @Test
    void createsVideoBusinessTaskInsideTheLocalTransactionAndBindsIt() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(1);
        when(taskRecords.insert(any(TaskRecord.class))).thenAnswer(call -> {
            TaskRecord record = call.getArgument(0);
            record.setId(501L);
            return 1;
        });
        when(tasks.bindBusinessId(org.mockito.ArgumentMatchers.eq(103L),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.eq(501L), any()))
                .thenReturn(1);
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.VIDEO_ANALYSIS,
                41L, "VIDEO_ANALYSIS:7:41:v1", "AUDIO_EXTRACT", 5, Map.of("videoMd5", "abc123"));
        TaskTransactionContext context = new TaskTransactionContext(
                "event-video", 103L, "topic", "VIDEO_ANALYSIS", command);

        var result = service.createOrReuse(context);

        assertThat(result.processingTaskId()).isEqualTo(103L);
        assertThat(result.businessId()).isEqualTo(501L);
        assertThat(result.reused()).isFalse();
        verify(taskRecords).insert(any(TaskRecord.class));
        verify(tasks).bindBusinessId(org.mockito.ArgumentMatchers.eq(103L),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.eq(501L), any());
    }

    @Test
    void reusesWinningVideoBusinessTaskWithoutCreatingAnotherRecord() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(0);
        ProcessingTask winner = new ProcessingTask();
        winner.setId(88L);
        winner.setEventId("event-video-winner");
        winner.setBusinessId(502L);
        when(tasks.selectOne(any())).thenReturn(winner);
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.VIDEO_ANALYSIS,
                41L, "VIDEO_ANALYSIS:7:41:v1", "AUDIO_EXTRACT", 5, Map.of("videoMd5", "abc123"));
        TaskTransactionContext context = new TaskTransactionContext(
                "event-video", 104L, "topic", "VIDEO_ANALYSIS", command);

        var result = service.createOrReuse(context);

        assertThat(result.processingTaskId()).isEqualTo(88L);
        assertThat(result.eventId()).isEqualTo("event-video-winner");
        assertThat(result.businessId()).isEqualTo(502L);
        assertThat(result.reused()).isTrue();
        verify(taskRecords, never()).insert(any(TaskRecord.class));
        verify(events, never()).insert(any(MqTransactionEvent.class));
    }

    @Test
    void invokesDeletionTargetLifecycleOnlyForTheNewTransactionalTask() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(1);
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.KNOWLEDGE_DELETE,
                31L, "KNOWLEDGE_DELETE:7:31", "DELETE_QUEUED", 10, Map.of());
        TaskTransactionContext context = new TaskTransactionContext(
                "event-delete", 105L, "topic", "KNOWLEDGE_DELETE", command);

        service.createOrReuse(context);

        ArgumentCaptor<TaskCreateCommand> created = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(targetLifecycle).onTaskCreated(created.capture(), any());
        assertThat(created.getValue()).isSameAs(command);
    }

    private static TaskTransactionContext context(Long taskId) {
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));
        return new TaskTransactionContext("event-1", taskId, "topic", "DOCUMENT_INGEST", command);
    }
}
