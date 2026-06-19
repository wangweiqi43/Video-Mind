package com.videomind.module.task.mq;

import com.videomind.module.task.service.TaskRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${videomind.rocketmq.dlq-topic.video-analyze}",
        consumerGroup = "${videomind.rocketmq.dlq-consumer-group.video-analyze}"
)
public class VideoAnalyzeDlqConsumer implements RocketMQListener<VideoAnalyzeMessage> {

    private final TaskRecordService taskRecordService;

    @Override
    public void onMessage(VideoAnalyzeMessage message) {
        String reason = "RocketMQ 重试超过最大次数，消息进入死信队列，等待人工补偿";
        taskRecordService.markFailed(message.getTaskId(), message.getUserId(), reason);
        log.error("Video analyze message entered DLQ, taskId={}, videoId={}, userId={}, md5={}",
                message.getTaskId(), message.getVideoId(), message.getUserId(), message.getVideoMd5());
    }
}
