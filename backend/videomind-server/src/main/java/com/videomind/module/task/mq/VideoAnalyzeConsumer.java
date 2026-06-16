package com.videomind.module.task.mq;

import com.videomind.module.task.service.VideoAnalyzeProcessorService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${videomind.rocketmq.topic.video-analyze}",
        consumerGroup = "${videomind.rocketmq.consumer-group.video-analyze}",
        maxReconsumeTimes = 3
)
public class VideoAnalyzeConsumer implements RocketMQListener<VideoAnalyzeMessage> {

    private final VideoAnalyzeProcessorService videoAnalyzeProcessorService;

    @Override
    public void onMessage(VideoAnalyzeMessage message) {
        videoAnalyzeProcessorService.process(message);
    }
}
