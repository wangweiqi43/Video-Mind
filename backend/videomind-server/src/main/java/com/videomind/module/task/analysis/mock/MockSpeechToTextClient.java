package com.videomind.module.task.analysis.mock;

import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.asr", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockSpeechToTextClient implements SpeechToTextClient {

    @Override
    public AsrResult transcribe(Long processingTaskId, AudioExtractionResult audio,
                                VideoFile videoFile, TaskRecord taskRecord) {
        String text = """
                这是 VideoMind 第三阶段生成的 Mock 转录文本。
                视频文件：%s。
                音频文件：%s。
                系统已经完成异步任务流转、本地 FFmpeg 音频提取、ASR 占位和摘要占位。
                后续阶段会把 Mock ASR 替换为真实第三方语音转文字接口。
                """.formatted(videoFile.getOriginalFilename(), audio.getAudioPath());
        return AsrResult.builder()
                .language("zh-CN")
                .text(text)
                .segments(List.of(new AsrSegmentResult(0,
                        Math.max(1, audio.getDurationSeconds() == null ? 180 : audio.getDurationSeconds()) * 1_000L,
                        text, 0)))
                .build();
    }
}
