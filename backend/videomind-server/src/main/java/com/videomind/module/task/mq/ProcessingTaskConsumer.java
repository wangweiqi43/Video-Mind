package com.videomind.module.task.mq;

import com.videomind.module.task.service.TaskEventConsumerService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${videomind.rocketmq.topic.processing-task}",
        consumerGroup = "${videomind.rocketmq.consumer-group.processing-task}",
        maxReconsumeTimes = 5
)
public class ProcessingTaskConsumer implements RocketMQListener<TaskEventMessage> {
    private final TaskEventConsumerService service;

    @Override
    public void onMessage(TaskEventMessage message) {
        service.consume(message);
    }
}
