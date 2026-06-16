package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.module.task.dto.TaskResultResponse;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.task.service.TaskResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskResultServiceImpl implements TaskResultService {

    private final TaskRecordService taskRecordService;
    private final VideoTranscriptionMapper videoTranscriptionMapper;
    private final AiSummaryResultMapper aiSummaryResultMapper;

    @Override
    public TaskResultResponse getTaskResult(Long taskId, Long userId) {
        TaskRecord taskRecord = taskRecordService.getTask(taskId, userId);
        VideoTranscription transcription = videoTranscriptionMapper.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getTaskId, taskId)
                .eq(VideoTranscription::getUserId, userId));
        AiSummaryResult summary = aiSummaryResultMapper.selectOne(new LambdaQueryWrapper<AiSummaryResult>()
                .eq(AiSummaryResult::getTaskId, taskId)
                .eq(AiSummaryResult::getUserId, userId));

        return TaskResultResponse.builder()
                .taskId(taskRecord.getId())
                .videoId(taskRecord.getVideoId())
                .status(taskRecord.getTaskStatus())
                .transcriptionText(transcription == null ? null : transcription.getTranscriptionText())
                .summaryText(summary == null ? null : summary.getSummaryText())
                .summaryJson(summary == null ? null : summary.getSummaryJson())
                .build();
    }
}
