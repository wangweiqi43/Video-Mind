package com.videomind.module.task.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskEventMessage;
import com.videomind.module.task.service.ConsumerInboxService;
import com.videomind.module.task.service.ProcessingTaskHandler;
import com.videomind.module.task.service.ProcessingTaskHandler.TaskExecutionContext;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import com.videomind.module.task.service.ProcessingTaskStateMachine.LeaseResult;
import com.videomind.module.task.service.ProcessingTaskStateMachine.LeaseStatus;
import com.videomind.module.task.service.TaskEventConsumerService;
import com.videomind.module.task.service.TaskRecordProjectionService;
import com.videomind.module.task.service.TaskCancellationException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TaskEventConsumerServiceImpl implements TaskEventConsumerService {
    private final MqTransactionEventMapper events;
    private final ConsumerInboxService inbox;
    private final ProcessingTaskStateMachine stateMachine;
    private final ObjectMapper objectMapper;
    private final TaskRecordProjectionService taskRecords;
    private final Map<com.videomind.common.enums.ProcessingTaskType, ProcessingTaskHandler> handlers;

    @Value("${videomind.rocketmq.consumer-group.processing-task}")
    private String consumerGroup;

    @Value("${videomind.rocketmq.processing-task.lease-seconds:300}")
    private long leaseSeconds;

    @Value("${videomind.rocketmq.processing-task.retry-delay-seconds:10}")
    private long retryDelaySeconds;

    public TaskEventConsumerServiceImpl(MqTransactionEventMapper events, ConsumerInboxService inbox,
                                        ProcessingTaskStateMachine stateMachine, ObjectMapper objectMapper,
                                        TaskRecordProjectionService taskRecords,
                                        List<ProcessingTaskHandler> handlers) {
        this.events = events;
        this.inbox = inbox;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
        this.taskRecords = taskRecords;
        this.handlers = new EnumMap<>(com.videomind.common.enums.ProcessingTaskType.class);
        for (ProcessingTaskHandler handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("duplicate task handler for " + handler.type());
            }
        }
    }

    @Override
    public void consume(TaskEventMessage message) {
        if (message == null || message.eventId() == null || message.eventId().isBlank()) {
            throw new NonRetryableTaskMessageException("TASK_EVENT_ID_MISSING");
        }
        MqTransactionEvent event = events.selectById(message.eventId());
        if (event == null || !"COMMITTED".equals(event.getTransactionState())) {
            throw new NonRetryableTaskMessageException("TASK_EVENT_NOT_COMMITTED");
        }
        ConsumerInboxService.ClaimResult claim = inbox.claim(consumerGroup, event.getEventId(), event.getTaskId());
        if (claim.status() == ConsumerInboxService.ClaimStatus.COMPLETED) {
            return;
        }

        TaskCreateCommand command = command(event);
        String owner = consumerGroup + ":" + UUID.randomUUID();
        LeaseResult lease = stateMachine.acquire(event.getTaskId(), owner, command.initialStage(),
                Duration.ofSeconds(Math.max(30, leaseSeconds)));
        if (lease.status() == LeaseStatus.TERMINAL || lease.status() == LeaseStatus.RETRY_EXHAUSTED) {
            taskRecords.project(event.getTaskId());
            inbox.complete(consumerGroup, event.getEventId());
            return;
        }
        if (lease.status() == LeaseStatus.NOT_FOUND) {
            throw new NonRetryableTaskMessageException("PROCESSING_TASK_NOT_FOUND");
        }
        if (lease.status() != LeaseStatus.ACQUIRED) {
            throw new RetryableTaskMessageException("TASK_LEASE_" + lease.status());
        }
        taskRecords.project(event.getTaskId());

        ProcessingTaskHandler handler = handlers.get(command.taskType());
        if (handler == null) {
            stateMachine.fail(event.getTaskId(), owner, lease.stateVersion(), command.initialStage(),
                    "TASK_HANDLER_MISSING", "没有注册任务处理器：" + command.taskType());
            taskRecords.project(event.getTaskId());
            inbox.complete(consumerGroup, event.getEventId());
            throw new NonRetryableTaskMessageException("TASK_HANDLER_MISSING");
        }
        try {
            String finalStage = handler.handle(new TaskExecutionContext(event.getTaskId(), event.getEventId(), command));
            if (!stateMachine.succeed(event.getTaskId(), owner, lease.stateVersion(), finalStage)) {
                if (stateMachine.cancellationRequested(event.getTaskId())) {
                    stateMachine.cancel(event.getTaskId(), owner);
                    taskRecords.project(event.getTaskId());
                    inbox.complete(consumerGroup, event.getEventId());
                    return;
                }
                throw new RetryableTaskMessageException("TASK_SUCCESS_CAS_LOST");
            }
            taskRecords.project(event.getTaskId());
            inbox.complete(consumerGroup, event.getEventId());
        } catch (TaskCancellationException cancelled) {
            stateMachine.cancel(event.getTaskId(), owner);
            taskRecords.project(event.getTaskId());
            inbox.complete(consumerGroup, event.getEventId());
        } catch (NonRetryableTaskMessageException known) {
            stateMachine.fail(event.getTaskId(), owner, lease.stateVersion(), command.initialStage(),
                    known.getMessage(), known.getMessage());
            taskRecords.project(event.getTaskId());
            inbox.complete(consumerGroup, event.getEventId());
            throw known;
        } catch (Exception failure) {
            boolean retrying = stateMachine.retry(event.getTaskId(), owner, lease.stateVersion(), command.initialStage(),
                    Duration.ofSeconds(Math.max(1, retryDelaySeconds)), "TASK_EXECUTION_FAILED",
                    failure.getMessage());
            if (retrying) {
                taskRecords.project(event.getTaskId());
            }
            throw new RetryableTaskMessageException("TASK_EXECUTION_FAILED", failure);
        }
    }

    private TaskCreateCommand command(MqTransactionEvent event) {
        try {
            TaskCreateCommand command = objectMapper.readValue(event.getPayloadJson(), TaskCreateCommand.class);
            if (command.taskType() == null || !command.taskType().name().equals(event.getTag())) {
                throw new NonRetryableTaskMessageException("TASK_EVENT_TYPE_MISMATCH");
            }
            return command;
        } catch (NonRetryableTaskMessageException known) {
            throw known;
        } catch (Exception invalid) {
            throw new NonRetryableTaskMessageException("TASK_EVENT_PAYLOAD_INVALID", invalid);
        }
    }

    public static class RetryableTaskMessageException extends RuntimeException {
        public RetryableTaskMessageException(String message) {
            super(message);
        }

        public RetryableTaskMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NonRetryableTaskMessageException extends RuntimeException {
        public NonRetryableTaskMessageException(String message) {
            super(message);
        }

        public NonRetryableTaskMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
