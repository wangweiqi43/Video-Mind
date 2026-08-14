package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskEventMessage;
import com.videomind.module.task.service.ConsumerInboxService;
import com.videomind.module.task.service.ProcessingTaskHandler;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import com.videomind.module.task.service.TaskRecordProjectionService;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.ProcessingTaskStateMachine.LeaseResult;
import com.videomind.module.task.service.ProcessingTaskStateMachine.LeaseStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TaskEventConsumerServiceImplTest {
    private final MqTransactionEventMapper events = mock(MqTransactionEventMapper.class);
    private final ConsumerInboxService inbox = mock(ConsumerInboxService.class);
    private final ProcessingTaskStateMachine stateMachine = mock(ProcessingTaskStateMachine.class);
    private final TaskRecordProjectionService taskRecords = mock(TaskRecordProjectionService.class);
    private final ProcessingTaskHandler handler = mock(ProcessingTaskHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TaskEventConsumerServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        when(handler.type()).thenReturn(ProcessingTaskType.DOCUMENT_INGEST);
        service = new TaskEventConsumerServiceImpl(events, inbox, stateMachine, objectMapper, taskRecords,
                List.of(handler));
        ReflectionTestUtils.setField(service, "consumerGroup", "group");
        ReflectionTestUtils.setField(service, "leaseSeconds", 300L);
        ReflectionTestUtils.setField(service, "retryDelaySeconds", 10L);
        when(events.selectById("event-1")).thenReturn(event());
    }

    @Test
    void completedInboxRecordStopsBeforeLeaseAndHandler() throws Exception {
        when(inbox.claim("group", "event-1", 9L)).thenReturn(
                new ConsumerInboxService.ClaimResult(ConsumerInboxService.ClaimStatus.COMPLETED));

        service.consume(new TaskEventMessage("event-1"));

        verify(stateMachine, never()).acquire(anyLong(), anyString(), anyString(), any());
        verify(handler, never()).handle(any());
    }

    @Test
    void acquiredLeaseRunsHandlerThenCasCompletesInbox() throws Exception {
        when(inbox.claim("group", "event-1", 9L)).thenReturn(
                new ConsumerInboxService.ClaimResult(ConsumerInboxService.ClaimStatus.CLAIMED));
        when(stateMachine.acquire(eq(9L), anyString(), eq("PARSE"), any()))
                .thenReturn(new LeaseResult(LeaseStatus.ACQUIRED, 4L));
        when(handler.handle(any())).thenReturn("PUBLISHED");
        when(stateMachine.succeed(eq(9L), anyString(), eq(4L), eq("PUBLISHED"))).thenReturn(true);

        service.consume(new TaskEventMessage("event-1"));

        verify(handler).handle(any());
        verify(taskRecords, org.mockito.Mockito.times(2)).project(9L);
        verify(inbox).complete("group", "event-1");
    }

    @Test
    void handlerFailureMovesTaskToRetryWaitAndRequestsRedelivery() throws Exception {
        when(inbox.claim("group", "event-1", 9L)).thenReturn(
                new ConsumerInboxService.ClaimResult(ConsumerInboxService.ClaimStatus.IN_PROGRESS));
        when(stateMachine.acquire(eq(9L), anyString(), eq("PARSE"), any()))
                .thenReturn(new LeaseResult(LeaseStatus.ACQUIRED, 4L));
        when(handler.handle(any())).thenThrow(new IllegalStateException("MinerU busy"));
        when(stateMachine.retry(eq(9L), anyString(), eq(4L), eq("PARSE"), any(),
                eq("TASK_EXECUTION_FAILED"), eq("MinerU busy"))).thenReturn(true);

        assertThatThrownBy(() -> service.consume(new TaskEventMessage("event-1")))
                .isInstanceOf(TaskEventConsumerServiceImpl.RetryableTaskMessageException.class);
        verify(taskRecords, org.mockito.Mockito.times(2)).project(9L);
        verify(inbox, never()).complete(anyString(), anyString());
    }

    @Test
    void cooperativeCancellationCompletesInboxWithoutRetry() throws Exception {
        when(inbox.claim("group", "event-1", 9L)).thenReturn(
                new ConsumerInboxService.ClaimResult(ConsumerInboxService.ClaimStatus.IN_PROGRESS));
        when(stateMachine.acquire(eq(9L), anyString(), eq("PARSE"), any()))
                .thenReturn(new LeaseResult(LeaseStatus.ACQUIRED, 4L));
        when(handler.handle(any())).thenThrow(new TaskCancellationException());
        when(stateMachine.cancel(eq(9L), anyString())).thenReturn(true);

        service.consume(new TaskEventMessage("event-1"));

        verify(stateMachine).cancel(eq(9L), anyString());
        verify(stateMachine, never()).retry(anyLong(), anyString(), anyLong(), anyString(), any(), any(), any());
        verify(inbox).complete("group", "event-1");
    }

    @Test
    void cancelWinsRaceAfterHandlerReturnsBeforeSuccessCas() throws Exception {
        when(inbox.claim("group", "event-1", 9L)).thenReturn(
                new ConsumerInboxService.ClaimResult(ConsumerInboxService.ClaimStatus.CLAIMED));
        when(stateMachine.acquire(eq(9L), anyString(), eq("PARSE"), any()))
                .thenReturn(new LeaseResult(LeaseStatus.ACQUIRED, 4L));
        when(handler.handle(any())).thenReturn("PUBLISHED");
        when(stateMachine.succeed(eq(9L), anyString(), eq(4L), eq("PUBLISHED"))).thenReturn(false);
        when(stateMachine.cancellationRequested(9L)).thenReturn(true);

        service.consume(new TaskEventMessage("event-1"));

        verify(stateMachine).cancel(eq(9L), anyString());
        verify(inbox).complete("group", "event-1");
    }

    private MqTransactionEvent event() throws Exception {
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));
        MqTransactionEvent event = new MqTransactionEvent();
        event.setEventId("event-1");
        event.setTaskId(9L);
        event.setTag("DOCUMENT_INGEST");
        event.setTransactionState("COMMITTED");
        event.setPayloadJson(objectMapper.writeValueAsString(command));
        return event;
    }
}
