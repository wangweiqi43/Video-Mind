package com.videomind.module.task.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.ProcessingTaskType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
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
                    ((TaskTransactionContext) call.getArgument(2)).resolve(77L, 31L, false);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setLocalTransactionState(LocalTransactionState.COMMIT_MESSAGE);
                    return result;
                });
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));

        TaskDispatchResult result = producer.dispatch(command);

        assertThat(result.processingTaskId()).isEqualTo(77L);
        assertThat(result.businessId()).isEqualTo(31L);
        assertThat(result.reused()).isFalse();
        assertThat(sent.get().getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo(result.eventId());
        assertThat(((TaskEventMessage) sent.get().getPayload()).eventId()).isEqualTo(result.eventId());
        verify(template).sendMessageInTransaction(eq("processing-topic:DOCUMENT_INGEST"), any(), any());
    }

    @Test
    void rejectsResolvedContextWhenRocketMqReportsRollback() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        RocketMqTransactionalTaskMessageProducer producer = new RocketMqTransactionalTaskMessageProducer(template);
        ReflectionTestUtils.setField(producer, "topic", "processing-topic");
        when(template.sendMessageInTransaction(eq("processing-topic:VIDEO_ANALYSIS"), any(), any()))
                .thenAnswer(call -> {
                    ((TaskTransactionContext) call.getArgument(2)).resolve(77L, 51L, false);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setLocalTransactionState(LocalTransactionState.ROLLBACK_MESSAGE);
                    return result;
                });
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.VIDEO_ANALYSIS,
                31L, "VIDEO_ANALYSIS:7:31:v1", "AUDIO_EXTRACT", 5, Map.of("videoMd5", "abc"));

        assertThatThrownBy(() -> producer.dispatch(command))
                .hasMessageContaining("本地事务未提交");
    }
}
