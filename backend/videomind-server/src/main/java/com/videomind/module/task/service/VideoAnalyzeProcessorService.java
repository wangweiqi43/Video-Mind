package com.videomind.module.task.service;

import com.videomind.module.task.mq.VideoAnalyzeMessage;

public interface VideoAnalyzeProcessorService {

    void process(VideoAnalyzeMessage message);
}

