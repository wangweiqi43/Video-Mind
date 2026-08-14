package com.videomind.module.task.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.videomind.common.exception.BizException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RocketMqTransactionalTaskMessageProducer implements TransactionalTaskMessageProducer {
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${videomind.rocketmq.topic.processing-task}")
    private String topic;

    @Override
    public TaskDispatchResult dispatch(TaskCreateCommand command) {
        validate(command);
        String eventId = UUID.randomUUID().toString();
        TaskTransactionContext context = new TaskTransactionContext(eventId, IdWorker.getId(), topic,
                command.taskType().name(), command);
        var message = MessageBuilder.withPayload(new TaskEventMessage(eventId))
                .setHeader(RocketMQHeaders.KEYS, eventId)
                .build();
        try {
            rocketMQTemplate.sendMessageInTransaction(topic + ":" + context.getTag(), message, context);
        } catch (Exception failure) {
            throw new BizException(503, "RocketMQ 事务消息发送失败：" + failure.getMessage());
        }
        if (context.getResolvedTaskId() == null) {
            throw new BizException(503, "RocketMQ 本地事务未返回任务结果");
        }
        return new TaskDispatchResult(eventId, context.getResolvedTaskId(), context.isReused());
    }

    private static void validate(TaskCreateCommand command) {
        if (command == null || command.userId() == null || command.taskType() == null
                || command.businessId() == null || command.businessFingerprint() == null
                || command.businessFingerprint().isBlank()) {
            throw new IllegalArgumentException("task command is incomplete");
        }
    }
}
