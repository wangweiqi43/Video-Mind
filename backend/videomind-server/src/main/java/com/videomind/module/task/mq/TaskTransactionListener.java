package com.videomind.module.task.mq;

import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.service.LocalTaskTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;

@Slf4j
@RequiredArgsConstructor
@RocketMQTransactionListener
public class TaskTransactionListener implements RocketMQLocalTransactionListener {
    private final LocalTaskTransactionService localTransactionService;
    private final MqTransactionEventMapper eventMapper;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object argument) {
        if (!(argument instanceof TaskTransactionContext context)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            var result = localTransactionService.createOrReuse(context);
            return result.reused()
                    ? RocketMQLocalTransactionState.ROLLBACK
                    : RocketMQLocalTransactionState.COMMIT;
        } catch (Exception failure) {
            log.error("RocketMQ local task transaction failed, eventId={}", context.getEventId(), failure);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String eventId = eventId(message);
        if (eventId == null || eventId.isBlank()) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            MqTransactionEvent event = eventMapper.selectById(eventId);
            if (event == null || "ROLLED_BACK".equals(event.getTransactionState())) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            return "COMMITTED".equals(event.getTransactionState())
                    ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.UNKNOWN;
        } catch (Exception transientDatabaseFailure) {
            log.warn("RocketMQ transaction check is temporarily unknown, eventId={}", eventId,
                    transientDatabaseFailure);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private static String eventId(Message message) {
        Object value = message.getHeaders().get(RocketMQHeaders.KEYS);
        return value == null ? null : value.toString();
    }
}
