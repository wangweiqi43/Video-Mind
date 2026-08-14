package com.videomind.module.task.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

class RocketMqTransactionalTaskMessageProducerTest {
    @Test
    void sendsHalfMessageWithEventKeyAndReturnsLocalTransactionResolution() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        RocketMqTransactionalTaskMessageProducer producer = new RocketMqTransactionalTaskMessageProducer(template);
        ReflectionTestUtils.setField(producer, "topic", "processing-topic");
        AtomicReference<Message<?>> sent = new AtomicReference<>();
        when(template.sendMessageInTransaction(eq("processing-topic:DOCUMENT_INGEST"), any(), any()))
                .thenAnswer(call -> {
                    sent.set(call.getArgument(1));
                    ((TaskTransactionContext) call.getArgument(2)).resolve(77L, false);
                    return null;
                });
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));

        TaskDispatchResult result = producer.dispatch(command);

        assertThat(result.taskId()).isEqualTo(77L);
        assertThat(result.reused()).isFalse();
        assertThat(sent.get().getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo(result.eventId());
        assertThat(((TaskEventMessage) sent.get().getPayload()).eventId()).isEqualTo(result.eventId());
        verify(template).sendMessageInTransaction(eq("processing-topic:DOCUMENT_INGEST"), any(), any());
    }
}
