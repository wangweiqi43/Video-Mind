package com.videomind.module.task.mq;

public interface AnalyzeTaskMessageProducer {

    void send(VideoAnalyzeMessage message);
}

