package com.videomind.module.task.mq;

import com.videomind.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RocketMqAnalyzeTaskMessageProducer implements AnalyzeTaskMessageProducer {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${videomind.rocketmq.topic.video-analyze}")
    private String topic;

    @Override
    public void send(VideoAnalyzeMessage message) {
        try {
            rocketMQTemplate.syncSend(topic, message);
        } catch (Exception ex) {
            throw new BizException(500, "发送视频解析任务消息失败：" + ex.getMessage());
        }
    }
}

