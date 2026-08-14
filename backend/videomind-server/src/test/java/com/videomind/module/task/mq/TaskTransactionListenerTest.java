package com.videomind.module.task.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.service.LocalTaskTransactionService;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

class TaskTransactionListenerTest {
    private final LocalTaskTransactionService local = mock(LocalTaskTransactionService.class);
    private final MqTransactionEventMapper events = mock(MqTransactionEventMapper.class);
    private final TaskTransactionListener listener = new TaskTransactionListener(local, events);

    @Test
    void localTransactionCommitsOnlyAfterDatabaseServiceSucceeds() {
        TaskTransactionContext context = new TaskTransactionContext("event-1", 1L, "topic", "TAG",
                new TaskCreateCommand(7L, com.videomind.common.enums.ProcessingTaskType.DOCUMENT_INGEST,
                        31L, "fp", "START", 5, java.util.Map.of()));
        assertThat(listener.executeLocalTransaction(message("event-1"), context))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);

        doThrow(new IllegalStateException("db down")).when(local).createOrReuse(any());
        assertThat(listener.executeLocalTransaction(message("event-1"), context))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void brokerCheckUsesDurableEventAsSourceOfTruth() {
        MqTransactionEvent committed = new MqTransactionEvent();
        committed.setTransactionState("COMMITTED");
        when(events.selectById("event-1")).thenReturn(committed, (MqTransactionEvent) null);

        assertThat(listener.checkLocalTransaction(message("event-1")))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
        assertThat(listener.checkLocalTransaction(message("event-1")))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void temporaryDatabaseFailureReturnsUnknownForLaterBrokerCheck() {
        when(events.selectById("event-1")).thenThrow(new IllegalStateException("timeout"));
        assertThat(listener.checkLocalTransaction(message("event-1")))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    private static org.springframework.messaging.Message<TaskEventMessage> message(String eventId) {
        return MessageBuilder.withPayload(new TaskEventMessage(eventId))
                .setHeader(RocketMQHeaders.KEYS, eventId).build();
    }
}
