package com.videomind.module.task.analysis.mock;

import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.summary", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockVideoSummaryClient implements VideoSummaryClient {

    @Override
    public SummaryResult summarize(AsrResult asrResult, VideoFile videoFile, TaskRecord taskRecord) {
        String summaryText = """
                ### 简洁摘要
                视频《%s》已完成异步解析，系统串联了视频上传、任务调度、音频提取、语音转文字和 AI 摘要生成等关键流程，为后续知识库检索和智能问答提供基础数据。

                ### 上传链路已写入对象存储和元数据
                视频文件会先进入上传流程，并保存到对象存储，同时在 MySQL 中记录文件名、MD5、大小和存储路径等元数据。

                ### 解析任务通过异步消息完成状态流转
                用户触发 AI 总结后，系统创建解析任务并交给消息队列异步消费，任务状态会从 PENDING 流转到 PROCESSING 和 SUCCESS。

                ### AI 结果被拆分保存便于后续扩展
                转录文本和摘要结果分别保存到独立表中，避免长文本和任务主表耦合，也方便后续接入向量化和智能助手。
                """.formatted(videoFile.getOriginalFilename());
        String summaryJson = """
                {"title":"Mock Video Summary","highlights":["异步任务已消费","Mock ASR 已生成转录文本","Mock 大模型已生成摘要"],"nextStep":"第四阶段接入本地 FFmpeg"}
                """;
        return SummaryResult.builder()
                .summaryText(summaryText)
                .summaryJson(summaryJson)
                .modelName("mock-summary@summary-v2")
                .build();
    }
}
